# FINNS 0.7.0｜F01 账号与身份维护（FINNS-15）：详细设计与接口契约（G4）

日期：2026-10-18。契约修订：3（修订 3：`FINNS_MAIL_SSL` 与全局日预算已实施；服务商选定 Brevo）。依据：[概要设计](DESIGN_ACCOUNT.md)、[账号需规修订7](../../requirements/FOUNDATION_ACCOUNT_REQUIREMENTS.md)、原型 [R2.1](ACCOUNT_PROFILE_R2_REVIEW.md)（模拟契约见 `docs/prototypes/site-access/src/mock/account.js` 与 `tests/account.test.mjs`）。

状态：**G4 通过并已按 B1–B7 实施**（用户 2026-10-18 授权 B1–B7 与新增邮件依赖，ACCOUNT-D09；B8 资料局部不含）。实施偏差与验证见 [实施记录](IMPLEMENTATION_ACCOUNT.md)（D1：`token_hash` 改为 `varchar(64)`；D2：新增 `account_state` 单行标记表；D3：会话保护范围）。本文件的契约内容以实施记录的偏差为准更新如下，§12 的 U1 已解决。

约定：JSON 字段 camelCase；时间为 ISO-8601 UTC；错误体沿用 `{ "status": <int>, "message": "<中文>" }`（`ApiExceptionHandler`）。所有非 GET 请求沿用 Cookie-to-header CSRF，缺失 → 403 `请求校验失败，请刷新页面后重试`。令牌与密码绝不出现在 URL 查询串、日志或响应中。

## 1. 与原型模拟契约的偏差（均不改变用户可见流程）

| 编号 | 原型 | 实际契约 | 原因 |
| --- | --- | --- | --- |
| X1 | `GET /api/auth/link?token=` 检查链接 | `POST /api/auth/link/inspect`，令牌放正文 | 查询串会进入代理/访问日志 |
| X2 | `signup-link` 与 `request-link` 两个接口 | 合并为 `POST /api/auth/request-link`，`purpose=signup\|reset` | 去重；页面传 `purpose=signup` |
| X3 | 公开 `request-link` 支持 `purpose=verify` | 删除；旧账号补验证改为登录后 `POST /api/account/verification-link` | 不需要把邮箱参数交给客户端；补验证只发生在已登录场景 |
| X4 | 公开响应 `cooldown` 为剩余秒数 | 公开响应恒为 `60`；仅登录态接口返回真实剩余秒数 | 公开剩余秒数会暴露邮箱是否在窗口内 |
| X5 | “登录状态预览”卡片展示三台模拟设备 | 生产页面不含该卡片 | 设备管理不在本次范围，原型已标注“预览” |
| X6 | 单个 `finns-account-session` 浏览器事件通知失效 | `api()` 在 401 时抛带 `status` 的错误，各页面通过 `sessionGuard` 处理 | 模拟事件不存在于真实环境 |
| X7 | `noticeFailed` 来自模拟开关 | 通知邮件提交后带 3 秒超时发送，失败/超时置 `noticeFailed:true` | 对应 P05 |

偏差表在实施验收时逐项核对；X5 是唯一会让界面少一块内容的偏差，已在最终说明中告知用户。

## 2. 数据（Flyway `V13__account_verification_and_recovery.sql`）

```sql
alter table user_accounts add column email_verified_at timestamp with time zone;
alter table user_accounts add column legacy_account boolean not null default false;
alter table user_accounts add column session_epoch bigint not null default 0;
alter table user_accounts add column password_changed_at timestamp with time zone;
update user_accounts set legacy_account = true;           -- 迁移时已存在的账号；不写 email_verified_at
create table account_state (id smallint primary key check (id = 1), legacy_marked_at timestamp with time zone not null);  -- 实施补充：旧账号过渡已执行的单行标记
insert into account_state (id, legacy_marked_at) values (1, current_timestamp);

create table account_action_tokens (
  id bigserial primary key,
  purpose varchar(10) not null check (purpose in ('SIGNUP','VERIFY','RESET')),
  email varchar(120) not null,                             -- 规范化（小写、去首尾空白）
  user_id bigint references user_accounts(id) on delete cascade,   -- SIGNUP 为空
  token_hash varchar(64) not null unique,                  -- SHA-256 十六进制；原文不落库（实施修订：char 与 Hibernate validate 不符）
  status varchar(10) not null check (status in ('PENDING','ACTIVE','VERIFIED','USED','SUPERSEDED','FAILED')),
  created_at timestamp with time zone not null,
  sent_at timestamp with time zone,
  expires_at timestamp with time zone not null,
  verified_at timestamp with time zone,
  used_at timestamp with time zone
);
create index idx_action_tokens_lookup on account_action_tokens (email, purpose, created_at desc);
create index idx_action_tokens_user on account_action_tokens (user_id, purpose, status);
create unique index ux_action_tokens_one_pending on account_action_tokens (email, purpose) where status = 'PENDING';
create unique index ux_action_tokens_one_live on account_action_tokens (email, purpose) where status in ('ACTIVE','VERIFIED');
```

- 令牌生成：`SecureRandom` 32 字节 → Base64URL（43 字符）；库中只存 SHA-256。
- `expires_at`：`SIGNUP`/`VERIFY` = 创建时 + 24 小时；`RESET` = 创建时 + 30 分钟。
- 清理：每次插入令牌时顺带删除 `expires_at` 早于 7 天前的行（最多 100 行），不引入定时任务。
- H2 开发库：实体 + ddl update；部分唯一索引在 H2 不可用，由服务层按同一规则保证；`AccountBootstrap`（启动，幂等，用标记行）把升级前已存在且无 `email_verified_at` 的账号置 `legacy_account=true`。`DemoData` 的演示账号创建时写入 `email_verified_at`。
- `UserAccount` 新增字段 `emailVerifiedAt`、`legacyAccount`、`sessionEpoch`、`passwordChangedAt`；密码哈希不进入任何 DTO。

## 3. 公共约定

### 3.1 邮箱规范化与校验

`trim` + 转小写；长度 ≤120；格式 `^[^\s@]+@[^\s@]+\.[^\s@]+$`（与前端一致）。格式不符 → 400 `请输入完整邮箱，例如 name@example.com。`（输入侧提示，P02 允许，且不回显输入值）。

### 3.2 `PasswordPolicy.validateNew(password, confirmPassword)`

按顺序，首个失败即返回 400：

1. 缺失 → `请设置登录密码。`
2. 码点数 < 8 → `密码至少需要8位。`；> 20 → `密码最多20位。`
3. UTF-8 字节数 > 72 → `密码包含较多特殊字符，请适当缩短。`（**不截断**）
4. 与确认不一致 → `两次输入的密码不一致。`

不 trim、不限制字符集、不要求组合。登录接口不套用此策略。

### 3.3 公开申请的统一响应

`POST /api/auth/request-link` 对以下全部情形返回**同一**响应：邮箱未注册、已注册、窗口内重复、小时上限、邮件通道关闭或发送失败、任务队列已满。

```json
200 { "message": "如果该邮箱可以接收账号邮件，我们会发送操作链接，请检查收件箱。", "cooldown": 60 }
```

邮箱格式错误（400）与来源 IP 限流（429 `操作过于频繁，请稍后再试。`）是仅有的区别，二者不依赖邮箱是否存在。

## 4. 公开接口（`AuthLinkController`，`@PublicEndpoint`，需 CSRF）

### 4.1 `POST /api/auth/request-link`

请求：`{ "email": "…", "purpose": "signup" | "reset" }`。响应见 §3.3（`signup` 额外返回 `"email"` 为规范化后的邮箱，供“等待验证”页展示）。

处理：校验邮箱 → IP 限流 → 判定资格（`signup`：该邮箱无账号；`reset`：账号存在，含旧账号）→ 不合格则立即返回统一响应，无副作用 → 事务 A：将超过 2 分钟的 `PENDING` 置 `FAILED`；若同 (purpose,email) 最近 `sent_at` 在 60 秒内，或过去 1 小时尝试 ≥5 次 → 返回统一响应；否则插入 `PENDING`（唯一索引冲突视为窗口内）→ 提交后把发送任务交给执行器（有界队列：2 线程 / 50 容量，满则把令牌置 `FAILED`）→ 返回统一响应。

发送任务：渲染邮件 → `MailSender.send` → 成功：事务 B 将同 (purpose,email) 的旧 `ACTIVE/VERIFIED` 置 `SUPERSEDED`，再把本令牌置 `ACTIVE` 并写 `sent_at`；失败：本令牌置 `FAILED`，旧令牌不动。

邮件链接：`SIGNUP` → `{base}/auth/verify?purpose=signup&token=…`；`RESET` → `{base}/auth/reset?purpose=reset&token=…`。旧账号未验证时 `RESET` 邮件标题与正文使用“验证邮箱并重置密码”版本（同一封、同一令牌，对应 ACCOUNT-D04）。

### 4.2 `POST /api/auth/link/inspect`

请求：`{ "token": "…", "purpose": "signup" | "verify" | "reset" }`。只读，不改变状态，不消耗。

200：`{ "email": "…", "purpose": "…", "expiresAt": "…", "verified": false, "needsVerification": false }`（`verified` 仅对 `signup` 有意义，表示已处于 `VERIFIED`；`needsVerification` 对 `reset` 表示该账号邮箱尚未验证，页面据此显示“本次操作会同时完成邮箱验证与密码重置”）。

错误：410，三种固定文案——令牌不存在/已被替代/用途不符：`链接无效或已被新链接替代，请重新申请。`；已使用：`这条链接已经使用，请重新申请。`；已过期：`链接已过期，请重新申请。`；以上均不泄露其他信息。来源 IP 限流同 §3.6。

### 4.3 `POST /api/auth/signup-verify`

请求：`{ "token": "…" }`。条件更新 `ACTIVE → VERIFIED`（已是 `VERIFIED` 且未过期则幂等返回）。200：`{ "email": "…" }`。无效/已用/过期同 §4.2 的 410。**不创建账号，不建立会话。**

### 4.4 `POST /api/auth/register`（替换旧一步注册）

请求：`{ "token": "…", "displayName": "…", "password": "…", "confirmPassword": "…" }`；请求中的 `email`、`role`、`userId` 等任何其他字段被忽略。

事务：校验昵称（trim 后 1–40）与 `PasswordPolicy` → 条件更新令牌 `VERIFIED → USED`（影响行数须为 1，否则 410；未 `VERIFIED` 的 `ACTIVE` 令牌 → 403 `请先验证邮箱，再设置账号。`）→ 以令牌邮箱插入账号（`email_verified_at=now`、`legacy_account=false`、默认角色由服务端分配）→ 把该邮箱其余令牌置 `SUPERSEDED`。邮箱唯一冲突 → 事务回滚（令牌仍为 `VERIFIED`）并返回 409 `注册无法完成，请尝试登录或找回密码。`。

201：`{ "email": "…", "message": "账号创建成功，请使用邮箱和密码登录。" }`。**不建立会话，不写 `USER_ID`。**

### 4.5 `POST /api/auth/verify`（旧账号补验证，用途 `VERIFY`）

请求：`{ "token": "…" }`。条件更新 `ACTIVE → USED` 并在同一事务写 `email_verified_at`（仅当为空）。**不触碰任何会话与 `session_epoch`。** 200：`{ "message": "邮箱已验证。", "legacy": true }`。

### 4.6 `POST /api/auth/reset`

请求：`{ "token": "…", "password": "…", "confirmPassword": "…" }`。

事务（顺序固定以避免死锁）：按令牌取 `user_id` → `select … for update` 锁用户行 → `PasswordPolicy`（失败时不消耗令牌）→ 条件更新 `ACTIVE → USED`（行数须为 1，否则 410）→ 写 `password_hash`、`password_changed_at`；`email_verified_at` 为空则写入；`session_epoch + 1` → 把该用户其余 `ACTIVE` 的 `RESET`/`VERIFY` 令牌置 `SUPERSEDED`。提交后：调用方会话（若有）`invalidate()`；发送改密通知（§6.3）。

200：`{ "message": "密码已重置，所有设备已退出，请重新登录。", "noticeFailed": false }`。**不自动登录。**

### 4.7 `POST /api/auth/login`（修改）

在原逻辑上调整：不存在的邮箱也执行一次对固定哈希的 BCrypt 比对以拉平耗时；**密码正确之后**才判定验证门槛：`email_verified_at is null and not legacy_account` → 403 `请先验证邮箱，再返回登录。`。成功：`request.changeSessionId()`，写 `USER_ID` 与 `AUTH_EPOCH`（取库中 `session_epoch`），刷新 CSRF。响应在原字段上追加 `emailVerified`。错误密码仍为 401 `邮箱或密码不正确`。

### 4.8 `GET /api/auth/me`（修改）

增加 `emailVerified`；会话 `AUTH_EPOCH` 与库中不一致 → 401 `登录已失效`。`POST /api/auth/logout`、`GET /api/auth/csrf` 不变。

## 5. 登录态接口（`AccountController`，`/api/account`，`@RequirePermission(PROFILE_MANAGE)`，需 CSRF）

`profile.manage` 为所有角色（含受限成员）锁定拥有，因此任何角色都可管理自己的密码。归属一律来自会话，请求中的 `userId`/`email` 被忽略。

### 5.1 `GET /api/account/security`

200：`{ "id", "email", "displayName", "role": {…}, "permissions": […], "emailVerified": true, "resetCooldown": 0, "verifyCooldown": 0 }`（后两项为该账号对应用途的真实剩余秒数；不返回设备列表，见 X5）。

### 5.2 `POST /api/account/password`

请求：`{ "oldPassword", "password", "confirmPassword" }`。

事务：锁用户行 → 原密码限流（每用户 15 分钟内失败 ≥5 次 → 429 `尝试过于频繁，请稍后再试，或通过邮箱重置密码。`）→ `encoder.matches(oldPassword, hash)`，失败 → 400 `原密码不正确，密码未改变。`（计入限流，不改任何状态）→ `PasswordPolicy` → 写哈希与 `password_changed_at`，`session_epoch + 1`，当前会话 `AUTH_EPOCH` 设为新值并 `changeSessionId()`；把其余 `ACTIVE` 的 `RESET`/`VERIFY` 令牌置 `SUPERSEDED`。提交后发送通知。

200：`{ "message": "密码已更新，当前设备保持登录，其他设备已退出。", "noticeFailed": false }`。并发的第二次提交会在行锁后因原密码已失效而 400，保证只有一次生效。

### 5.3 `POST /api/account/password-reset-link`

请求体忽略（`{}`）。收件人为会话用户的邮箱。流程与 §4.1 `reset` 相同，但响应的 `cooldown` 为**真实剩余秒数**，且不做“账号存在”隐藏（调用者已登录，是自己的邮箱）。申请**不改密码、不撤销会话**。200：`{ "message": …统一文案, "cooldown": 60 }`。

### 5.4 `POST /api/account/verification-link`

仅对 `email_verified_at is null` 的会话用户有效，已验证 → 200 统一响应且不发信。流程同 §4.1，用途 `VERIFY`，链接 `{base}/auth/verify?purpose=verify&token=…`。

## 6. 邮件

### 6.1 配置（环境变量，凭据不入库/文档/日志）

| 变量 | 含义 | 默认 |
| --- | --- | --- |
| `FINNS_MAIL_MODE` | `disabled` \| `smtp` \| `outbox` | `disabled` |
| `FINNS_PUBLIC_BASE_URL` | 链接前缀，如 `https://example.org` | 无（`smtp` 模式必填，缺失则启动失败） |
| `FINNS_MAIL_HOST` / `PORT` / `USERNAME` / `PASSWORD` | SMTP | — |
| `FINNS_MAIL_FROM` | 发信地址 | — |
| `FINNS_MAIL_STARTTLS` | 是否 STARTTLS | `true` |
| `FINNS_MAIL_SSL` | 隐式 TLS（465），为 true 时忽略 STARTTLS；STARTTLS 为“必须”，两种方式都校验服务器证书主机名；有 SMTP 账号却无 TLS 时拒绝启动（已实施） | `false` |
| `FINNS_MAIL_DAILY_BUDGET` | 全局日发送预算（审计时区自然日）：已发令牌邮件 + 在途 + 通知邮件（进程内计数）；用尽后新令牌邮件按发送失败处理，通知邮件放行但计入（已实施） | `0`（不限）；真实配置建议服务商日额度的 80%，Brevo 为 240 |

`outbox` 模式把邮件写入 `data/dev-mail/`（已被 `.gitignore` 的 `data/` 覆盖），仅当 `server.address` 为回环地址时启动，否则拒绝启动。`disabled` 模式：申请接口按 §3.3 响应、不发信也不记录链接；账号创建因此不可用。

### 6.2 邮件内容

五种纯文本邮件（无追踪像素、无远程图片）：注册验证、旧账号补验证、密码重置、旧账号“验证并重置”、密码已更新通知。仅含链接和有效期说明；**不含密码、旧密码或其他账号数据**。通知邮件包含操作时间与“如果不是你本人，请立即通过忘记密码重置”，不含 IP/设备。

### 6.3 日志

仅记录事件类型、令牌 ID、邮箱的 SHA-256 前 12 位、结果；不记录邮箱明文、令牌原文、链接、密码。

## 7. 会话撤销（`SessionGuard`）

- 常量 `AuthController.AUTH_EPOCH = "AUTH_EPOCH"`。
- `AccessService.forUser`：SQL 增加 `u.session_epoch`，`AccessContext` 增加 `sessionEpoch` 字段（构造点共 4 处：`guest()` 与 `AccessService` 3 处）；`resolve(session)` 在 `AUTH_EPOCH` 与之不一致（含缺失）时返回游客上下文，拦截器因会话仍带 `USER_ID` 而响应 401 `登录已失效`。
- `AuthController.requireUser`：已加载实体，直接比对 `getSessionEpoch()`；不一致 → 401 `登录已失效`。
- `SessionRevocationService.revokeAll(userId)`：`session_epoch + 1`（供 FINNS-16 使用）。
- 不一致的会话不主动销毁；超时后由容器回收。

## 8. 前端

| 项 | 内容 |
| --- | --- |
| 路由 | 新增 `/auth/login`、`/auth/register`、`/auth/pending`、`/auth/verify`、`/auth/setup`、`/auth/forgot`、`/auth/reset`（组件 `AccountFlowPage`，`meta.accountMode`）、`/me/security`（`requiresAuth`）、`/me/profile/edit` |
| 守卫 | 未登录访问需登录路由 → `/auth/login?next=<站内路径>`；`/?signin=1&next=` 旧链接重定向；`next` 须以 `/` 开头且不以 `//` 开头 |
| `App.vue` | 删除认证弹窗，`openAuth` 改为路由跳转；其余与现有一致 |
| `lib/api.js` | 抛出的 Error 附加 `status`；其余行为不变 |
| `lib/sessionGuard.js` + 面板 | 受保护请求 401 → 就地重新登录，比对 `user.id`；用于资料编辑、发布表单、评论框；专题编辑器/AI 工作台沿用各自的草稿队列，实施前逐页核对其 401 行为并记录（见 §12 U3） |
| 密码输入 | 仅组件内存；成功、离开、卸载时清空；不写 localStorage/草稿/日志 |
| 样式 | 原型 `account.css` 并入前端资产，不引入新库 |
| 响应头 | `SpaController` 对 `/auth/**` 返回 `Referrer-Policy: no-referrer`、`Cache-Control: no-store`；增加 `/auth/**` 回退 |
| 不移植 | `AccountLab`、`ProtoConsole`、`mock/*`、`AccountDraftPage`（演示稿）、设备预览卡 |

## 9. 资料局部（FINNS-21 衔接，**非本批次范围**）

原型含只读资料页与 `/me/profile/edit`。FINNS-21 整体 G1 尚未通过，故这部分仅记录接口影响，**实施须待用户在 FINNS-21 上单独确认**：`GET /api/me/profile` 追加 `emailVerified`；`PATCH /api/me/profile` 追加 `expectedUserId`（与会话用户不一致 → 409 `账号已切换，请使用原账号重新登录后保存。`）；其余保持（昵称 1–40、简介 ≤500，归属来自会话）。F01 的重新登录面板可独立先行。

## 10. 验收与测试映射

测试编号 T-*；类型：M = MockMvc（H2）、P = PostgreSQL 隔离库（`scripts/test-postgres.ps1`）、F = 前端 Node 测试、B = 浏览器。均为**待编写**，未执行。

| 需规 | 场景 | 测试 |
| --- | --- | --- |
| ACCOUNT-01/08 | 提交邮箱 → 检查 → 验证 → 设置账号 → 登录；验证前无账号、无会话；`register` 忽略 `email/role`；重复使用同一令牌 410；登录接口对未验证新账号 403；注册响应不含 `Set-Cookie` 会话 | T-A01 M、T-A02 M、T-A03 M |
| ACCOUNT-02 | 仅令牌所属账号被改；`userId` 参数无效；过期/已用/被替代均 410 且不改密 | T-A04 M |
| ACCOUNT-03 | 申请与 `link/inspect` 重复调用不改密、不增 epoch、不退出 | T-A05 M |
| ACCOUNT-04 | 原密码错误 400 且无变化；成功后当前会话保留、另一会话 401；限流 429 不影响登录 | T-A06 M、T-A07 M |
| ACCOUNT-05 | 旧账号未验证可登录；单独补验证保留会话；旧未验证账号一封邮件验证并重置、全部退出、`email_verified_at` 写入；新账号不能走旧账号路径；历史账号未被批量标记 | T-A08 M、T-A09 M、T-A10 P（迁移） |
| ACCOUNT-06 | 已注册/未注册/窗口内/发送失败的响应体、状态码一致；发送失败保留旧链接、成功替代旧链接；60 秒/小时上限 | T-A11 M、T-A12 M、T-A13 M |
| ACCOUNT-07 | 桌面 1440、平板 768、手机 390；直达/刷新 `/auth/*`、前进后退；登录状态变化 | T-A14 B |
| P01 | 7/8/20/21 位边界；20 个 4 字节字符被拒（字节上限）；两次不一致；BCrypt 对 73 字节的静默截断由 T-P01 用已解析的 6.3.6 实测固定（证明策略必要且无回归） | T-P01 M、T-P02 M |
| P02 | 同 T-A11；格式错误 400 不回显输入 | T-A11 M |
| P03 | 失败保留页面与邮箱；密码不进入 localStorage/日志（扫描日志与存储）；失败后不产生半成功密码状态 | T-A15 F、T-A16 M |
| P04 | 401 后内容保留、就地登录；换账号拒绝提交；撤销后代表性受保护接口均 401 | T-A17 F、T-A18 M、T-A19 B |
| P05 | 成功后发出通知且不含密码/凭据；通知失败不回滚且 `noticeFailed:true` | T-A20 M |
| P06 | 成功后其余 `ACTIVE` 令牌置 `SUPERSEDED`；并发两次重置/两次改密/两次注册恰好一次成功 | T-A21 P、T-A22 P、T-A23 P |
| 横切 | 全部 `/api/**` 仍有 `@PublicEndpoint` 或 `@RequirePermission`；CSRF 缺失 403；令牌不出现在日志 | 既有遍历测试 + T-A24 M |
| 迁移 | V13 在隔离库：空库、含旧账号库、重复启动；回退 0.7.0 旧包可登录 | T-A25 P |

## 11. 实施批次（待授权）

| 批次 | 内容 | 依赖 |
| --- | --- | --- |
| B1 | V13、实体字段、`PasswordPolicy`、`AccountBootstrap`、测试夹具改为直接创建已验证账号（`AccessTestSupport` 与 4 处内联注册） | — |
| B2 | `session_epoch` 贯通：`AccessContext`、`AccessService`、`requireUser`、`SessionRevocationService` | B1 |
| B3 | 令牌服务、状态机、`MailSender` 三实现、执行器、限流、清理 | B1 |
| B4 | 公开接口（§4）与登录改造，`SpaController` 增加 `/auth/**` 与响应头 | B2、B3 |
| B5 | 登录态接口（§5）与通知 | B2、B3 |
| B6 | 前端：路由、页面移植、`api()` status、`sessionGuard`、样式 | B4、B5 |
| B7 | 两个 Node 验收脚本改用开发出信箱；`.env.example`、`docs/DATABASE.md`/运行说明；浏览器与 PostgreSQL 验证；CHANGELOG | B6 |

资料局部（§9）另列 B8，需 FINNS-21 单独确认后才排期。

## 12. 待用户决定与限制

| 编号 | 事项 | 影响 |
| --- | --- | --- |
| U1 | ~~实施授权：B1–B7 的范围（含新增 `spring-boot-starter-mail`）~~ | **已解决（ACCOUNT-D09）**：授权 B1–B7 与邮件依赖，B8 不含 |
| U2 | **邮件服务**：按[概设 §3.3.1](DESIGN_ACCOUNT.md)推荐 Brevo（首选）或 Resend（备选）的免费档，SMTP 接入；**需要用户确认：是否已有自有发信域名**，并自行在服务商注册、配置 SPF/DKIM/DMARC，经环境变量提供主机/端口/凭据/发件人；`FINNS_PUBLIC_BASE_URL`。已实施：`FINNS_MAIL_SSL`、`FINNS_MAIL_DAILY_BUDGET`（契约修订 3）。**用户已提供域名 2000678.xyz，选定 Brevo**；账号注册、DNS 与凭据待用户操作，见 [MAIL_SETUP.md](MAIL_SETUP.md) | 不阻断 B1–B7（出信箱代替）；阻断真实邮件验收与 G8 |
| U3 | 专题编辑器与 AI 工作台的 401 保护：实施前核对既有行为，若已有等价保护则记录证据，否则纳入 B6 | 范围确认，默认纳入 |
| U4 | 可选文案：等待验证页追加“也可能该邮箱已注册，可前往登录或找回密码” | 默认不加；加则属于文案变更 |
| U5 | FINNS-21 资料查看/编辑局部是否随 F01 同批实施 | 默认不含（§9） |

限制：本契约没有任何运行证据；§3.6 的数值是设计默认值；进程内限流在重启/多实例下不共享；真实邮件投递率、延迟和退信未验证；当前部署仅监听本机回环，邮件中的链接只在同一台机器上可用，除非配置公网基础地址。
