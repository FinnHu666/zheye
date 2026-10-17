# 0.7.0 / 0.8.0 可点击原型（G2）

覆盖 FINNS-10（首页浏览量）、FINNS-11（管理员与受限访问）、FINNS-13（租房私人专题）、FINNS-12（自定义评价模块与榜单）。

- 全部为**模拟数据**，不连接后端、不改数据库；它不证明真实并发计数、持久化、接口权限、CSRF 或服务端校验。
- 使用项目已有的 `frontend/node_modules`（与 `topic-builder` 原型相同的做法），不新增依赖。

## 运行

```powershell
cd E:\code\FINNS\AI-tome\docs\prototypes\access-rating
node ..\..\..\frontend\node_modules\vite\bin\vite.js
# 打开 http://127.0.0.1:4174/  （不占用 8080 / 8082）
```

构建输出到 `target/prototypes/access-rating`。页面顶部可切换桌面 / 768 / 390 宽度；“确认清单”页列出需用户确认的工作草案参数（勾选不等于确认）。

## 验证状态

- 容器内 Chromium：5 个页面 × 3 个宽度无控制台错误、无横向滚动；关键交互通过（见 docs 记录）。
- 未在用户本机用 Vite 运行过；以上在容器中用等价的 Vue 浏览器构建验证。G2 仍待用户确认具体原型版本与范围。
