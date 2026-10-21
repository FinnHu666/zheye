FINNS-17 原型 R3（需规修订6，待评审）：[记录与体验步骤](../../iterations/0.7.0/PROTOTYPE_REVIEW_R3.md)。审批队列 <http://127.0.0.1:4175/admin/reviews>、规则与词库导入预览 <http://127.0.0.1:4175/admin/sensitive-rules>、作者流转 `/me/submissions`；右下“审核演示 · R3”。三级敏感、统一流程、流转时间线（含预留阶段）、重做的审批页；52项模拟测试与构建通过，全部为浏览器虚构数据。以下 R1/R2 内容为历史。

# 当前：FINNS-17 内容审核原型 R2（待评审）

[具体范围与验证](../../iterations/0.7.0/PROTOTYPE_REVIEW_R2.md)。内容审批 <http://127.0.0.1:4175/admin/reviews>、规则管理 <http://127.0.0.1:4175/admin/sensitive-rules>；右下“审核演示 · R2”→“载入长文审阅样例”体验完整阅读。首次无旧版栏，已有发布版单独切换。规则支持搜索/状态/分页/新增/编辑/启停/删除确认和操作记录。

运行命令沿用下方，当前50项模拟与97模块构建通过；证据 `target/prototypes/review-r2-proof/`。G2待用户评审，CRUD增量未冻结/写Plane，真实业务未实施。下方R1及其他原型记录为历史。

# 当前新增：FINNS-17内容审核原型R1（2026-10-18）

入口 <http://127.0.0.1:4175/admin/reviews>；首次体验可先到 <http://127.0.0.1:4175/review/reading>，右下“审核演示 · R1”→“载入审核协作样例”。可切换站长/作者/独立审核员/无审批权管理员/游客，无需真实凭据。新增独立路由：`/admin/reviews`、`/admin/sensitive-rules`、`/me/submissions`、`/review/reading`；具体步骤见[R1记录](../../iterations/0.7.0/PROTOTYPE_REVIEW.md)。

前两次敏感命中拦截、第3次人工；专题仅首次对外发布审批、禁止自审；规则只有站长能维护；旧版、下架恢复、失败/冲突/停用场景均可模拟。作者/读者是F08片段，未接入真实业务。需规修订3已通过，**R1仍待用户评审**，不以模拟当作实际验收或发布。

运行复用现有4175服务与前端依赖，无需安装；在本目录运行 `node --test tests/account.test.mjs tests/user-admin.test.mjs tests/review.test.mjs`（44项模拟）和 `node ../../../frontend/node_modules/vite/bin/vite.js build`。最终检查通过；截图/日志/18文件快照位于忽略目录`target/prototypes/review-r1-proof/`，可被清理。旧用户/账号原型仍可使用，下方原说明保留历史。

## FINNS-16：R3及范围已通过（2026-10-18）

用户在[R3展示](../../iterations/0.7.0/PROTOTYPE_USER_ADMIN_R3.md)后回复“通过”（USER-ADMIN-D11），需求修订2、G1增量与G2均通过，勿重复索要R3批准。站长转交候选/原因与邮箱确认/原站长降级/会话/审计按R3冻结；业务UA-A01–A15仍全部未执行。P02完整正文差异修订1已备妥，待单项核对写入授权，本轮未连接Plane；下一步处理P02，然后概要设计/设计评审与详设契约，不因原型批准直接开发或发布。仅本地文档记录，本轮未重跑原型测试/浏览器，历史25项模拟及构建证据保留；代码、数据库、主站和版本均未改。下方待R3评审为历史。

# 当前：FINNS-16用户管理原型R3（2026-10-18）

入口 <http://127.0.0.1:4175/admin/users>，左下“用户管理演示 · R3”载入站长与管理员样例，点击“转交站长”。站长/管理员边界已确认，支持页面转交；正常管理员候选人、原站长变管理员、原因/邮箱确认及会话方案待[R3评审](../../iterations/0.7.0/PROTOTYPE_USER_ADMIN_R3.md)。失败/冲突开关可体验，全部为浏览器虚构数据。

已有依赖下，在本目录执行`node --test tests/account.test.mjs tests/user-admin.test.mjs`（25项）及`node ../../../frontend/node_modules/vite/bin/vite.js build`。本轮模拟/构建通过，桌面940px与390px检查；业务/真实安全未实施，未写Plane。下方旧版记录为历史。

# 当前新增：FINNS-16用户管理原型R2（2026-10-18）

入口 <http://127.0.0.1:4175/admin/users>。三页版式、管理弹窗已统一；左下“用户管理演示 · R2”可载入站长/管理员样例并切换管理员体验同级保护。[R2记录/待确认范围/验证](../../iterations/0.7.0/PROTOTYPE_USER_ADMIN_R2.md)。新增站长的具体管理/转交边界暂按推荐方案演示，待用户回答，未批准为正式需规；原型本身亦待评审。22项模拟测试与构建通过，940px/390px浏览器检查完成。真实业务/Plane/数据库/发布均未操作，下方R1内容仅历史。

# 当前新增：FINNS-16用户管理原型R1（2026-10-18）

入口 <http://127.0.0.1:4175/admin/users>。左下“用户管理演示 · R1”→“载入双管理者样例”，搜索阿远，体验停用/恢复、原因必填与二次确认；操作记录可按账号状态查询。面板另有失败/冲突/最后管理者和演示稿会话失效场景。新浏览器可直接用场景按钮进入，无需输入真实密码。

[完整评审步骤、验证及限制](../../iterations/0.7.0/PROTOTYPE_USER_ADMIN.md)。R1已制作、待用户确认；21项模拟测试及构建/桌面与390px/768px检查通过。复用本目录原型，不新增技术栈、不开发业务或发送真实邮件。下方账号/权限各版说明保留历史，F07当前状态以R1记录为准。

# FINNS 0.7.0 原型：网站访问量 + 按角色限制访问（R4）

## 当前原型R2.1：登录中忘记原密码

在`/me/security`的登录密码卡片点击“忘记原密码？通过邮箱重置”，直接向当前演示账号申请邮件；无需重新填邮箱或旧密码。申请不退出，完成重置后全部退出。右下角模拟收件箱打开邮件；刷新后保留60秒重发冷却。[R2.1记录](../../iterations/0.7.0/ACCOUNT_PROFILE_R2_REVIEW.md)。模拟测试当前12项；此前R2/R1说明为历史，具体原型仍待用户确认。


## 当前评审版本：账号与基础资料R2（2026-10-18）

入口 <http://127.0.0.1:4175/auth/register>：先邮箱验证，再设置昵称/密码，最后正常登录；密码上限20位，错误用行内提示。移除左侧介绍，改为居中卡片。`/me`默认只读，“修改资料”到`/me/profile/edit`独立页面。右下角账号演示含资料保存失败开关。

[完整变更/评审步骤/验证与待同步范围](../../iterations/0.7.0/ACCOUNT_PROFILE_R2_REVIEW.md)。本轮模拟测试11/11通过；R2待用户确认，未开发真实后端/邮件或业务资料页。以下R1/R4段落为历史，注册顺序和新密码上限以R2为准。


## FINNS-15 账号原型 R1（2026-10-17）

已在本套原型内扩展注册/邮箱验证/找回/改密、旧账号过渡和未保存内容保护，**待用户评审R1**。入口 <http://127.0.0.1:4175/auth/register>，右下角“账号演示”提供模拟收件箱和失败场景；[评审步骤/验证记录](../../iterations/0.7.0/PROTOTYPE_ACCOUNT.md)。邮件、密码核对及设备均模拟，请只使用虚构信息；输入密码不保存。本轮未开发真实后端或资料独立编辑。

已有依赖下执行 `node --test tests/account.test.mjs`（7项），构建/启动沿用下文。以下R4说明为原网站访问/权限原型的历史基础，账号登录入口现改为独立页面。


覆盖 FINNS-10（首页全站累计访问量）与 FINNS-11（基于用户角色的访问管理），依据 [0.7.0 需规修订 3](../../iterations/0.7.0/REQUIREMENTS.md)。原型记录、草案参数与验证见 [PROTOTYPE_SITE_ACCESS.md](../../iterations/0.7.0/PROTOTYPE_SITE_ACCESS.md)。

## 和旧原型的区别

- 页面直接加载 `frontend/src` 的**真实** FINNS 外壳、页面组件、`api.js` 与 `styles.css`，看到的就是当前网站加上 0.7.0 改动后的样子。
- 只替换 0.7.0 需要改的 4 个文件（见 `src/overlays/`，由 `vite.config.mjs` 的解析钩子替换）：`App.vue`（访问上报、角色导航、403 页）、`pages/DiscoverPage.vue`（首页第 4 个数字“累计访问”、按角色隐藏互动）、`components/PersonalNav.vue`（按角色隐藏入口）、`pages/CollectionEditorPage.vue`（R4：可见范围）。
- 新增页面：`/admin/users`、`/admin/roles`（R2 起可修改各角色权限，R3 起可新增、改名、删除角色）、`/admin/audit`（R2 起支持筛选查询）（`src/pages/`）。
- `src/mock/` 在浏览器内拦截 `/api/*`：模拟会话、CSRF 校验、角色矩阵、受限专题、访问去重。数据只存在于本浏览器 localStorage。
- 旧的 [access-rating](../access-rating/README.md) 原型基于旧口径（内容对象计数、单一受限资格），对 FINNS-10/11 已被本原型取代，仅作历史保留。

## 运行

```powershell
cd E:\code\FINNS\AI-tome\docs\prototypes\site-access
```

```powershell
node ..\..\..\frontend\node_modules\vite\bin\vite.js
```

打开 http://127.0.0.1:4175/ （不占用 8080/8082/4174）。复用 `frontend/node_modules`，不新增依赖。构建检查：`node ..\..\..\frontend\node_modules\vite\bin\vite.js build`，输出到已忽略的 `target/prototypes/site-access`。

## 怎么体验

左下角紫色虚线框“原型控制台”（不是产品功能）：

- **身份**：游客 / 管理员 / 受信成员 / 普通成员 / 受限成员一键切换；也可在“登录”里输入 `admin@finns.test`、`zhou@finns.test`、`yuan@finns.test`、`chen@finns.test`（模拟登录，密码任意）。
- **浏览量**：刷新、打开新标签页、31 分钟后、次日、换浏览器；切换 30/60 分钟/自然日规则；模拟读取慢、读取失败、上报失败、刚启用为 0。
- **权限演练**：直接调用管理接口、给他人提权、注册时提交 `role=ADMIN`、另一位管理员改我的角色、缺少 CSRF、保存失败、会话过期。
- **请求日志**：看每个接口的状态码，确认不是只靠隐藏按钮。
- **待确认**：V1–V5、R1–R7 草案参数。

## 不能证明什么

原型不证明真实并发计数、持久化与重启恢复、服务端授权实现、真实 CSRF、缓存一致性或数据库迁移；这些在 G3–G6 设计和测试。未模拟与 0.7.0 无关的既有写入接口（专题草稿编辑、导入、AI 生成、本地工具），这些页面会如实显示“原型未模拟此功能”。
