# FINNS 0.7.0｜F07 账号停用与用户管理衔接（FINNS-16）：概要设计（G3）

日期：2026-10-18。修订：1。输入：[需求修订2](../../requirements/FOUNDATION_USER_ADMIN_REQUIREMENTS.md)（UA-R01–R15）、[已确认原型 R3](PROTOTYPE_USER_ADMIN_R3.md)（USER-ADMIN-D11）、[验收映射](ACCEPTANCE_USER_ADMIN.md)（UA-A01–A15）、当前源码。评审记录：[DESIGN_REVIEW_USER_ADMIN.md](DESIGN_REVIEW_USER_ADMIN.md)。详设与接口：[CONTRACT_USER_ADMIN.md](CONTRACT_USER_ADMIN.md)。

状态：设计，不是已实现能力。设计不新增产品范围；与 R3 的偏差见契约 §1。

## 1. 现状（以 2026-10-18 源码为准）

| 事实 | 位置 |
| --- | --- |
| 用户只有一个角色（`role_id`），权限由 `role_permissions` 决定；每次请求从库读取，不缓存到会话 | `AccessService.forUser/resolve` |
| 系统角色只有 `ADMIN`、`DEFAULT`（`roles.system_kind`），各有唯一部分索引 | `V12__roles_and_access.sql` |
| “管理者”＝角色含 `admin.roles`；保护规则按权限而非角色名称判断 | `AccessAdminService.managerCount` |
| 所有角色/权限/分配写操作在一个事务内锁定 `access_control_state` 单行，比对版本 | `AccessAdminService.lockVersion/checkVersion` |
| 会话撤销：`user_accounts.session_epoch`，登录写入会话，`AccessService.resolve` 与 `AuthController.requireUser` 比对 | FINNS-15 |
| 创建登录会话的代码只有 `AuthController.login` 一处；密码重置不建立会话 | 全源码 `setAttribute(USER_ID` 仅一处 |
| 操作记录表 `access_audit_log` 的 `type` 与 `action` 有 CHECK 约束，只含角色相关取值 | V12 |
| 用户表没有账号状态；用户管理接口没有停用/恢复；角色没有“站长”概念；首位管理员由 `AccessBootstrap` 按配置邮箱指定 | 源码 |

## 2. 设计总览

```
浏览器 ──(会话 cookie + CSRF)──> PermissionInterceptor ──> AccessAdminController
                                      │ AccessService.resolve: 停用/epoch 不符 → 按游客处理（“登录已失效”）
                                      v
                        AccessAdminService（一个事务 = 全局锁 + 校验 + 写 + 审计）
                          ├─ changeStatus   停用/恢复（本项新增）
                          ├─ transferOwner  站长转交（本项新增）
                          └─ assignRole / 权限 / 角色定义（既有，加站长层级与“正常管理者”口径）
                                      │
        user_accounts(account_status,status_version,session_epoch)   roles(+OWNER)
        access_control_state(version, owner_user_id, owner_version)   access_audit_log(+状态/结果列)
```

## 3. 关键设计决定

### 3.1 账号状态：两列，会话复用 `session_epoch`

`user_accounts.account_status`（`ACTIVE/DISABLED`，默认 `ACTIVE`）与 `status_version`（每次状态变更 +1，供并发核对）。停用与恢复都使 `session_epoch + 1`。

三个强制点，任何一个失败即拒绝：
1. `AuthController.login`：密码正确后才检查停用，返回“账号已停用，请联系站点管理员。”（UA-R08）；错误密码/不存在邮箱沿用统一提示。
2. `AccessService.resolve`：停用账号按游客处理，受保护接口返回“登录已失效”。
3. `AuthController.requireUser`：停用即拒绝（多数个人接口走这里，不经拦截器）。

被否决：新建会话存储表（FINNS-15 已决定不引入，epoch 足够）；只靠 epoch 不加状态检查（直接改库或恢复时的竞态下少一道防线，检查成本是已加载的一列）。

### 3.2 站长：独立系统角色 `OWNER` + 唯一指针 `owner_user_id`

- 新增系统角色 `OWNER`（`system_kind='OWNER'`，拥有全部权限，权限不可关闭，不可改名/复制/删除/被分配）。部分唯一索引保证只有一个该类角色。
- “谁是站长”的唯一事实源是 `access_control_state.owner_user_id`（单行表，天然唯一）；持有 `OWNER` 角色的用户必须等于该指针。`owner_version` 在每次转交时 +1，供转交确认时核对（UA-R15 陈旧信息）。
- 一致性由三层保证：①应用层任何路径都不能分配/移除 `OWNER`（普通分配、定义、迁移均拒绝）；②转交在同一事务里同时交换两人角色与指针；③启动时 `AccessBootstrap` 做幂等自愈（见 3.6）。

被否决：用户表加布尔列 `is_site_owner`（与角色形成两个事实源）；只用指针不引入角色（站长权限无法复用既有“每次请求按角色取权限”的机制，需要到处特判）；固定“最早注册的管理员”为站长（与可转交冲突）。

### 3.3 层级与保护规则（UA-R07、R11、R12）

“受保护角色”＝系统种类 `OWNER`/`ADMIN`，或权限含 `admin.roles`。“受保护用户”＝其角色受保护。

| 操作 | 站长 | 非站长（含管理员） |
| --- | --- | --- |
| 停用/恢复 | 可：任何非站长用户 | 仅非受保护用户 |
| 分配角色 | 可：非站长用户 → 除 `OWNER` 外的任意角色 | 仅非受保护用户 → 非受保护角色 |
| 修改受保护角色的权限、编辑/删除受保护角色、授予任何 `admin.*` 权限、复制受保护角色 | 可（`OWNER` 角色除外） | 拒绝（403） |
| 对站长本人 | 不可停用、不可直接改角色、不可通过删除角色或迁移改变 | 拒绝 |
| 转交 | 仅当前站长，接任人＝另一位正常的 `ADMIN` 角色用户 | 拒绝 |

所有操作还须满足：不能停用自己；“正常管理者”（正常 + 角色含 `admin.roles`）操作后至少一位；停用账号不计入。

### 3.4 并发：一把全局锁

停用、恢复、转交、角色分配、权限、角色定义全部先 `select … for update` 锁 `access_control_state` 行，再读写。这一次把“并发停用对方”“与角色/权限变更竞争”“并发转交”全部串行化（UA-A09、A15），代价是管理写操作互斥——管理操作频率极低，可接受。比较了：对用户行加锁（需要稳定的跨行锁序，且无法覆盖“角色权限变更影响管理者计数”）、`SERIALIZABLE` 隔离（需要重试逻辑，H2/PG 行为不一致）。

操作者的“是否站长/是否仍正常”在锁内重读，不使用请求入口读取的快照（转交与其他管理操作并发时，入口快照可能已过期）。

### 3.5 操作记录

沿用 `access_audit_log`：类型新增 `ACCOUNT_STATUS`、`OWNER_TRANSFER`；动作新增 `DISABLE/RESTORE/TRANSFER/INIT`；新增列 `before_status/after_status/outcome/result_message`。记录写在业务事务内（成功时与变更原子）；校验失败/保存失败的“拒绝/失败”记录在独立事务提交，保证回滚不吞掉记录（UA-R02、R15）。拒绝记录只在操作者已通过入口权限、目标存在时写入，避免未授权请求污染；记录不含密码、令牌、会话。

### 3.6 站长初始化与迁移（原型要求“在 G3/G4 设计”的部分）

`V14` 只加结构与 `OWNER` 角色，不指定站长。`AccessBootstrap`（启动，幂等）按顺序：①确保 `OWNER` 角色存在（H2 无迁移）；②若 `owner_user_id` 为空：优先部署配置的首位管理员邮箱（若是正常管理者或尚无管理者则直接成为站长），否则选 id 最小的正常管理者；没有任何管理者则只记警告，等首位管理员注册；③自愈：站长指针用户的角色置为 `OWNER`；其他持有 `OWNER` 的用户（例如回退到旧程序包期间被误分配）降为 `ADMIN` 并写入“系统初始化”记录。**生产迁移时谁成为站长已由用户决定（USER-ADMIN-D13）：部署配置邮箱对应的正常账号优先，即使已有其他管理员、且它尚不是管理员。**

### 3.7 转交的会话处理（UA-R14）

同一事务内：交换角色与指针、`owner_version+1`、`access_control_state.version+1`、双方 `session_epoch+1`。控制器随后对**当前请求会话**换会话 ID 并写入操作者的新 epoch（与 FINNS-15 主动改密同一手法），因此原站长当前设备保持登录且立即按管理员权限处理；双方其他设备失效；接任人所有会话失效须重新登录。邮箱核对是防误选步骤，不是真实再认证（需求已声明）。

### 3.8 前端

沿用 Vue 3 栈。把已确认原型的三页共用外壳（`AdminLayout`）、居中弹窗外壳（`AdminDialog`）及样式移植进 `frontend/src`，用户页增加状态列/筛选/停用恢复两步确认/转交入口，角色页与操作记录页按站长层级调整。登录失败文案直接展示服务端消息。原型只用于对照，不被生产代码引用。

## 4. 兼容、迁移与回退

- `V14` 全部为附加变更（新列带默认值、放宽 CHECK、新增一个角色），不改写业务数据；对 `finns` 主库的迁移须先备份并做恢复演练（G8 前置）。
- 回退到不含本项的程序包：**停用账号将重新可以登录**（旧包不认识状态列）。用户已决定**不支持回退到旧包**（USER-ADMIN-D13），该风险作为已知局限保留，发布流程不安排此回退。`OWNER` 角色在旧包里表现为一个普通的全权限角色，可被旧包分配——下一次升级启动时由 3.6 的自愈纠正。
- 新增依赖：无。

## 5. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 管理写操作全局互斥造成等待 | 锁内只做少量 SQL；测试覆盖并发 |
| 拒绝记录可被有权限者刷屏 | 仅已授权操作者、已存在目标才记；已知局限，管理员行为可追责 |
| 非站长管理员无法自助降级 | 与 R3 一致（管理员不得调整管理员）；站长可代为处理 |
| 回退使停用失效 | 见 §4，G8 回退步骤显式列出 |
| 角色总数上限 12 含 `OWNER` | 与原型一致；已有 12 个角色的库迁移后不能再新增，需先删除不用的角色 |

## 6. 技术栈变动说明

无新增依赖、无框架升级、无外部服务；沿用 Java 17、Spring Boot 3.3、JdbcTemplate、Flyway、Vue 3。

## 7. 验收映射

见契约 §10（UA-A01–A15 → 测试）。
