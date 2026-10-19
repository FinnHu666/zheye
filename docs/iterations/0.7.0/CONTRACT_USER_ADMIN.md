# FINNS 0.7.0｜F07 账号停用与用户管理衔接（FINNS-16）：详细设计与接口契约（G4）

日期：2026-10-18。修订：1。依据：[概要设计](DESIGN_USER_ADMIN.md)、[设计评审](DESIGN_REVIEW_USER_ADMIN.md)、[需求修订2](../../requirements/FOUNDATION_USER_ADMIN_REQUIREMENTS.md)、[R3 原型](PROTOTYPE_USER_ADMIN_R3.md)。

**实施授权**：用户 2026-10-18 在本任务中要求“完成这个 Plane 上的任务”并说明“原型已经开发完毕”。AI 将其理解为授权按本契约实施 B1–B5（见 §11），**不含**部署到 8080、Git 提交/推送、发布、主库迁移、真实邮件、Plane 写入。若用户本意仅为设计，请告知，实施内容可整体不采纳。

## 1. 与原型 R3 的偏差（均不改变用户可见流程）

| # | 原型 | 生产 | 理由 |
| --- | --- | --- | --- |
| D1 | 角色用 `key/label`，请求字段 `role/expectedRole` | 沿用生产接口 `role.code/role.name`、`roleCode/expectedRoleCode` | 不改既有前后端契约 |
| D2 | 管理员改自己的角色时提示“管理员不能管理其他管理员” | 提示“不能调整自己的角色，请由站长处理。”（仍拒绝） | 原文案对自己不准确；行为一致 |
| D3 | 转交确认邮箱须与接任人完全一致 | 去首尾空白、不区分大小写 | 邮箱库内为小写，大小写不构成误选保护 |
| D4 | `unchanged` 作为响应字段 | 目标状态已是当前状态时返回 200 和最新条目，不写记录；前端以条目状态判断 | 不增加响应字段 |
| D5 | 审计“拒绝”覆盖所有失败 | 仅操作者已通过入口权限、目标存在时记录；未知用户 404、未授权不记 | 防未授权请求污染记录（设计评审 C-04） |
| D6 | 转交候选＝角色 `ADMIN` 且正常 | 同；以系统种类 `ADMIN` 判定而非角色名称 | 角色可改名 |

## 2. 数据（Flyway `V14__account_status_and_site_owner.sql`）

附加变更；PostgreSQL 专用。H2 开发库由实体（`ddl-auto: update`）建列，`AccessBootstrap` 补 `OWNER` 角色。

```sql
alter table user_accounts add column account_status varchar(10) not null default 'ACTIVE' check (account_status in ('ACTIVE','DISABLED'));
alter table user_accounts add column status_version bigint not null default 1;

alter table roles drop constraint if exists roles_system_kind_check;
alter table roles add constraint roles_system_kind_check check (system_kind in ('ADMIN','DEFAULT','OWNER'));
-- 已有同名自定义角色时给系统角色另取名，避免迁移因 ux_roles_name_lower 失败
insert into roles (code,name,description,system_kind,sort_order,created_at,updated_at)
select 'OWNER', case when exists (select 1 from roles where lower(name)='站长') then '站长（系统）' else '站长' end,
       '唯一站点所有者，管理管理员；仅通过专门入口转交', 'OWNER', 1, current_timestamp, current_timestamp
where not exists (select 1 from roles where system_kind='OWNER');
insert into role_permissions (role_id, permission)
select r.id, p.permission from roles r
cross join (values ('content.public'),('profile.manage'),('selection.manage'),('post.create'),('post.interact'),('collection.manage'),
                   ('ai.use'),('content.restricted'),('admin.users'),('admin.audit'),('admin.roles')) as p(permission)
where r.system_kind='OWNER' and not exists (select 1 from role_permissions x where x.role_id=r.id and x.permission=p.permission);

alter table access_control_state add column owner_user_id bigint references user_accounts(id) on delete set null;
alter table access_control_state add column owner_version bigint not null default 1;

alter table access_audit_log drop constraint if exists access_audit_log_type_check;
alter table access_audit_log add constraint access_audit_log_type_check check (type in ('ROLE_ASSIGN','ROLE_PERMISSION','ROLE_DEFINE','ACCOUNT_STATUS','OWNER_TRANSFER'));
alter table access_audit_log drop constraint if exists access_audit_log_action_check;
alter table access_audit_log add constraint access_audit_log_action_check check (action in ('CREATE','UPDATE','DELETE','DISABLE','RESTORE','TRANSFER','INIT'));
alter table access_audit_log add column before_status varchar(10);
alter table access_audit_log add column after_status varchar(10);
alter table access_audit_log add column outcome varchar(10) not null default 'SUCCESS' check (outcome in ('SUCCESS','REJECTED','FAILED'));
alter table access_audit_log add column result_message varchar(200);
```

`OWNER` 的权限显式列出 11 个权限码（不依赖 `ADMIN` 现状，设计评审 C-06）。**约束名在 PostgreSQL 隔离库用 `pg_constraint` 核对**；名称不符则迁移后插入 `OWNER` 会失败，测试会暴露。

实体同步（H2/validate）：`UserAccount`（只读映射两列，状态只经 SQL 变更）、`AccessControlState`、`AccessAuditEntry`、`RoleRow.isOwner()`、`Role.KIND_OWNER`。

## 3. 规则函数（`AccessAdminService` 内部）

- `Operator lockedOperator(ctx)`：锁 `access_control_state` 行后，读操作者当前状态、角色种类、权限；停用/不存在 → 401“登录已失效”；`owner = kind==OWNER && id==owner_user_id`。
- `isProtectedRole(role)`：种类 `OWNER`/`ADMIN` 或当前权限含 `admin.roles`。
- `restrictionFor(op, target, nextRole)` 返回 403 文案或空：
  1. 目标是站长 → “站长不能停用或直接调整角色，请由当前站长使用专门的转交入口。”
  2. 目标受保护且操作者非站长 → 自己：“不能调整自己的角色，请由站长处理。”（仅分配角色）；他人：“管理员不能管理其他管理员，请由站长处理。”
  3. `nextRole` 为 `OWNER` → “站长为唯一身份，请使用专门的转交入口。”
  4. `nextRole` 受保护且操作者非站长 → “只有站长可以授予管理员身份或管理角色权限。”
- `activeManagerCount(permissionsByRole, countsByRole)`：计数口径只含 `account_status='ACTIVE'`；所有“最后管理者”检查与列表 `managerCount` 用它。

## 4. 接口（`AccessAdminController`，`/api/admin`，需 CSRF；写请求均在一个事务内）

### 4.1 `GET /users`（`admin.users`）
参数：`q`（≤80）、`role`、`status`（空/`ACTIVE`/`DISABLED`，其他 400“账号状态不正确”）、`page`、`size`。组合筛选，`order by created_at,id`。

条目（相对**当前操作者**计算的提示字段，服务端写接口仍独立校验）：
`id, displayName, email, role{code,name}, canManage, createdAt, accountStatus, statusVersion, siteOwner, canAssignRole, roleBlockReason, canChangeStatus, statusBlockReason`。
`canChangeStatus=false` 的原因：自己 →“不能停用自己的账号。”；`restrictionFor` 的文案。分页体增加 `managerCount`（正常管理者数）。

### 4.2 `PATCH /users/{id}/status`（`admin.users`）
请求 `{status:"DISABLED"|"ACTIVE", expectedVersion:long, note:string}`；响应 200 条目。

顺序：404 用户不存在 → 400 状态无效 →（以下失败写审计）400 原因（去首尾空白 1–200 字，按码点）→ 422 停用自己“不能停用自己的账号，请由另一位有权限的管理者操作。”→ 403 `restrictionFor` → 目标已是目标状态：200，不写记录 → 409 `expectedVersion` 不符“该账号状态已被其他管理者更新，请核对最新状态后再次确认。”→ 422 停用后正常管理者为 0“操作后将没有正常管理者，请先安排另一位正常且能管理角色的用户。”→ 写：`account_status`、`status_version+1`、`session_epoch+1` → 成功记录。数据库异常 → 503“暂时无法保存，账号状态未改变。原因已保留，请重试。”并记 FAILED。

### 4.3 `PATCH /users/{id}/role`（既有，扩展）
在原校验前后加入 `restrictionFor`、`activeManagerCount` 口径；响应条目带新增字段。拒绝类失败写 `ROLE_ASSIGN` 以外的记录？**不写**（既有行为保持，不扩大范围）。

### 4.4 `GET /roles`、`PUT /roles`、`POST/PATCH/DELETE /roles/definitions…`（既有，扩展）
- `RoleItem` 增加 `assignable`（≠`OWNER` 且 站长或非受保护）、`manageable`（≠`OWNER` 且 站长或非受保护）。
- `OWNER`：权限全部锁定；不可改名/复制/删除/被分配/作为迁移目标。
- 非站长：拒绝修改受保护角色权限、授予任何 `admin.*`、编辑/删除/复制受保护角色、迁移到受保护角色（403，文案见 §3.4/§3 中同类）。
- 站长用户在被删除角色的成员中，或迁移目标为 `OWNER` → 403“站长身份不能通过删除角色或迁移用户改变。”
- 角色总数上限 12 含 `OWNER`。

### 4.5 `GET /ownership`（`admin.users`，且须为当前站长，否则 403“只有当前站长可以转交站长身份。”）
响应 `{owner:{id,displayName,email}, version, candidates:[{id,displayName,email}]}`。候选＝正常且角色种类 `ADMIN` 的其他用户，不受列表筛选/分页影响，按注册时间排序。

### 4.6 `POST /ownership`
请求 `{targetId, expectedOwnerId, expectedVersion, note, confirmEmail}`。失败写 `OWNER_TRANSFER` 记录（操作者通过入口权限即记）。

顺序：锁 → 403 非当前站长 → 409 `expectedOwnerId`/`expectedVersion` 与库不符“站长信息已变化，请重新核对接任人后确认。”→ 422 接任人不存在/是自己/不是正常 `ADMIN` 角色用户“接任人必须是另一位正常管理员，请重新选择。”→ 400 原因 1–200 字“请填写1至200字的转交原因。”→ 400 `confirmEmail` 不符“请输入接任人的完整邮箱以确认转交。”→ 写：操作者角色 `ADMIN`、接任人角色 `OWNER`、`owner_user_id`、`owner_version+1`、`state.version+1`、双方 `session_epoch+1` → 成功记录。控制器随后：`changeSessionId()` 并把当前会话 `AUTH_EPOCH` 设为操作者新 epoch。响应 `{owner:{…}, formerOwnerRole:{code,name}}`。

### 4.7 `GET /audit`（`admin.audit`，既有，扩展）
`type` 新增 `ACCOUNT_STATUS`、`OWNER_TRANSFER`；条目新增 `beforeStatus, afterStatus, outcome, resultMessage`；`q` 同时搜索 `result_message`。`role` 筛选对 `ACCOUNT_STATUS` 条目匹配目标当时角色。

## 5. 登录、会话与找回

- `POST /api/auth/login`：顺序＝邮箱不存在/密码错 → 401 统一文案；**停用 → 403“账号已停用，请联系站点管理员。”**；未验证 → 既有 403。不向本人展示管理原因。
- `AccessService.resolve`：停用 → 游客；`AuthController.requireUser`：停用 → 401“登录已失效”。退出（公开接口）不受影响。
- 找回密码（FINNS-15）：不修改；停用账号可申请并完成重置，重置不建立会话、不改状态；`session_epoch` 已随停用抬高。
- 作品可见性：不修改任何内容可见性代码路径（UA-R06）。

## 6. 记录字段映射

| 类型 | operator | target | role_* | before/after | 其他 |
| --- | --- | --- | --- | --- | --- |
| `ACCOUNT_STATUS`（action `DISABLE`/`RESTORE`） | 操作者 | 目标用户 | `role_code/name`＝目标当时角色 | `before_status/after_status`（失败时 after=before） | `note`＝原因，`outcome`，`result_message` |
| `OWNER_TRANSFER`（`TRANSFER`/`INIT`） | 原站长（初始化为“系统初始化”，id 空） | 接任人 | `before_role_*`＝接任人原角色，`after_role_*`＝站长 | — | 同上 |

名称为写入时快照。成功记录与变更同事务；拒绝/失败记录用独立事务；记录失败只记日志，不掩盖原始错误。

## 7. 启动自愈（`AccessBootstrap`，幂等）

1. 确保 `OWNER` 角色及全部权限存在。
2. `owner_user_id` 空：部署配置邮箱对应的正常账号（USER-ADMIN-D13：即使已有其他管理员、且它尚不是管理员，也优先）→ 否则 id 最小的正常管理者 → 写 `INIT` 记录。无可指定者则警告并等待。
3. 站长指针用户角色置 `OWNER`；其他持 `OWNER` 者降为 `ADMIN` 并写 `INIT` 记录。

首位管理员路径：无任何管理者时，配置邮箱用户直接成为站长（不再先成为 `ADMIN`）。

## 8. 前端（`frontend/src`）

- 新增 `components/AdminLayout.vue`、`components/AdminDialog.vue`、`assets/admin.css`（从已确认原型移植并去掉模拟依赖），三管理页共用；`AdminNav` 保留。
- `pages/admin/AdminUsersPage.vue`：状态列/筛选（URL 查询同步、前进后退）、概览（结果数、正常管理者数）、停用/恢复两步确认（原因必填 1–200、失败保留、409 重读、已是目标状态提示）、转交两步弹窗（候选、原因、邮箱核对、409/422 重读保留原因）、调整角色弹窗仅列 `assignable`、受限原因文案。站长判断＝`currentUser.role.code==='OWNER'`。
- `pages/admin/AdminRolesPage.vue`：站长角色卡只读；非站长隐藏受保护角色的编辑/删除、锁定 `admin.*` 开关；复制/迁移目标过滤。
- `pages/admin/AdminAuditPage.vue`：新增类型筛选与展示（结果、状态变化、站长转交）。
- 登录与重新登录面板展示服务端文案（含“账号已停用…”）。
- 失效：停用导致 401 时沿用 FINNS-15 `sessionGuard`（未提交内容保留、换账号禁提交）。

## 9. 兼容性

接口为加字段/加参数，不移除既有字段；旧前端（缓存）忽略新字段；`GET /users` 旧客户端不传 `status` 等同全部。

## 10. 验收与测试映射

| 验收 | 测试（H2 `AccessAdminTests`/新 `UserStatusTests`/`OwnerTransferTests`；PG 隔离库同套） | 位置 |
| --- | --- | --- |
| UA-A01 | 列表含 `accountStatus`；状态×搜索×角色×分页组合；非法状态 400 | `UserStatusTests` |
| UA-A02 | 原因空白/201 字/取消(无请求)；停用后资料、角色、草稿保留；响应与列表一致 | `UserStatusTests` |
| UA-A03 | 正确密码→403 停用文案不含原因；错误密码/不存在邮箱统一；受保护页与 API 401；退出 204 | `UserStatusTests` |
| UA-A04 | 两个会话停用后均 401；恢复后旧会话仍 401；重新登录成功、角色/资料不变 | `UserStatusTests` |
| UA-A05 | 停用中申请并完成重置：密码更新、仍停用、旧会话失效；恢复后新密码可登录，旧密码不可 | `UserStatusTests`（复用 FINNS-15 测试辅助） |
| UA-A06 | 停用前后已发布作品列表/详情可见范围不变；无新增下架逻辑（源码未改） | `UserStatusTests` + 静态说明 |
| UA-A07 | 游客/普通/无权限/被撤权者 401/403；伪造 id/越级 403；无/错 CSRF 403；失败不改状态 | `UserStatusTests` |
| UA-A08 | 自停用 422；最后正常管理者 422；角色分配/权限/删除迁移不能只留停用管理者 | `UserStatusTests` |
| UA-A09 | 两线程并发互相停用/与角色变更竞争：最终≥1 正常管理者；旧版本 409 | `UserStatusTests`（并发，H2 与 PG） |
| UA-A10 | 成功/拒绝/失败各一条且字段完整，无凭据；失败无部分变更；重复提交无重复生效/记录 | `UserStatusTests` |
| UA-A11 | 停用后旧会话 401（后端）；前端沿用 FINNS-15 的会话保护（面板、保留输入、换账号禁提交） | `UserStatusTests`；`frontend/tests/session.test.js`（既有）；F07 专项浏览器演练列为用户验收 V9 |
| UA-A12 | 桌面/390/768、直达刷新/前进后退、加载/失败/取消/冲突 | 浏览器核对 |
| UA-A13 | 站长↔管理员↔成员矩阵；直接 API 越权；角色复制/删除/迁移/授权 | `OwnerTransferTests` |
| UA-A14 | 转交成功交换、原站长当前会话仍可用且为管理员、接任人旧会话 401、他人其他会话 401；取消不变；无候选；原因/邮箱校验 | `OwnerTransferTests` |
| UA-A15 | 并发/重复/陈旧版本唯一站长；失败无部分变更；记录三种结果；`OWNER` 不可经角色接口改变；初始化与自愈 | `OwnerTransferTests`、`AccessBootstrapTests` |

迁移验证：隔离 PG 库 `finns_test_*` 上从 V13 状态升级到 V14（复制库演练）、重复启动无重复迁移、CHECK 约束名核对；不连接 `finns` 主库。

## 11. 实施批次

| 批 | 内容 |
| --- | --- |
| B1 | V14、实体、`AccessBootstrap`（OWNER/自愈）、`Permission/Role/RoleRow` |
| B2 | 登录/会话强制点、`changeStatus`、用户列表扩展、审计扩展 |
| B3 | 层级规则接入既有角色接口、`transferOwner`、`ownership` 接口 |
| B4 | 前端：共用外壳、三管理页、登录文案、前端测试 |
| B5 | 后端测试（H2、PG 隔离库）、浏览器核对、记录与交接 |

## 12. 用户决定与限制

- **已决定（USER-ADMIN-D13）**：生产站长账号由用户提供，发布时经 `FINNS_BOOTSTRAP_ADMIN_EMAIL` 配置（邮箱不入库）；**不支持回退到旧包**（旧包不认识停用状态，回退会使停用账号重新可登录，已接受并不再为此设计）。
- 邮箱核对不是真实再认证；站长转交无邮件通知（需求范围外）。
- 本批不含：真人手机/读屏验收、真实多设备、生产数据演练；发布（G8）需另行授权，并先做主库备份与恢复演练。
