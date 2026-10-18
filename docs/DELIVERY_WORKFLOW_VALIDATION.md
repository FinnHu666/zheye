# 开发流程与 Plane 适配验证

2026-10-13，修订 1。交付范围为流程 skill、只读检查器、当前状态记录及已有决定一致性修复。应用保持 0.6.0。

## 实现与用法

入口：[交付 skill](../.agents/skills/finns-delivery-workflow/SKILL.md) → [适配协议](../.agents/skills/finns-delivery-workflow/references/progress-check.md) → [功能进度](delivery-progress.json)。技术开发 skill 和 AGENTS 引导同一流程，不另设竞争入口。

从项目根执行（Python 3.9+，仅标准库）：

```powershell
python -X utf8 .agents/skills/finns-delivery-workflow/scripts/check_progress.py --root . --state docs/delivery-progress.json
```

当前预期退出 2，表示有待办：conversation 在 G1；views/access/rating/rental 在 PLANE。它报告登记证据，不自行推断源码完成率。每次由 AI 阅读证据及本轮授权，输出具体动作；未知证据不能判为通过。无后台任务、无联网、无 Plane 写入。

## 验证结果

- `scripts/test_progress.py`：8 个行为测试通过，覆盖有效/变化/丢失/空证据、不适用缺理由、前序阻断、越界路径、重复门禁。
- 使用 subprocess 核对当前五项输出及真实退出码 2，UTF-8 JSON 可解析，阶段符合当前本地证据。
- 两个 skill 的官方 `quick_validate.py` 通过。初次按 Windows 默认编码执行失败，改用 `-X utf8` 后通过；不能把首次失败记为成功。
- 规则走查：用户“继续 0.7.0”应先检查并提示剩余范围/同步；用户“解释 Vue computed”不触发；要求直接开发但原型未确认时阻断依赖动作；Cycle 建好但无工作项不得算同步完成；重复同一批准不再询问；普通资料准备可继续。这是本地规则走查，未做独立代理或真实 MCP 故障测试。
- 本轮未运行业务构建、浏览器、数据库、收费 AI 或发布验证，因为没有业务变更。历史批准快照和需规 0.5 未编辑。

## 后续维护

新证据录入阶段记录并绑定修订/源码，再更新进度证据哈希。脚本验证结构和证据完整性，AI 验证语义及授权；不能宣称其强制约束所有客户端或用户。完整业务阶段不得从配置删除。Plane 当前状态需要在获得相应指令后实时核对，历史回执不能代替实时读取。

## 2026-10-18 增补：G3 设计评审轮

新增 [设计评审](../.agents/skills/finns-delivery-workflow/references/design-review.md)，并接入 [阶段门禁](../.agents/skills/finns-delivery-workflow/references/stage-gates.md) 的 G3、交付 skill 和开发 skill 的各一处指引；没有新增门禁编号，进度文件结构不变。记录模板新增 `DESIGN_REVIEW_<功能>.md`。

规则走查（本地，非独立代理）：
- 适用：FINNS-15 概设修订 2 引入公共邮件服务商 → 触发评审（外部服务/第三方额度）；按专项规则读取官方定价页并标日期，读不到的写“未核实”；产出 [账号概设评审](iterations/0.7.0/DESIGN_REVIEW_ACCOUNT.md)，7 个条件分别落到关闭/转入下游/不采纳。
- 相邻但不触发：修复已确认行为的小缺陷（如样式）、纯文档/skill 修改、普通技术问答 → 规则明确可豁免，需要在记录里写豁免理由；不会因此新增评审文件。
- 失败情景：没有授权启动独立评审者 → 只能“同作者自评”，必须写明独立性有限（本轮即如此）；条件没有去向 → G3 保持待办；官方页面读取被拒绝 → 对应服务商写“未核实”，不能写进结论。
- 脚本：`scripts/test_progress.py` 8 项通过，`check_progress.py` 对当前进度退出 2（真实待办）；两个 SKILL.md 的 frontmatter 与本地链接核对通过。官方 `quick_validate.py` 本轮未运行。

限制：这是规则走查，没有让独立代理实际按新规则评审；评审规则能否在真实的设计争议中发现问题，要靠后续使用检验。
