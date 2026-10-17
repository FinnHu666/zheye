# FINNS 0.7.0 原型：网站访问量 + 按角色限制访问（R4）

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
