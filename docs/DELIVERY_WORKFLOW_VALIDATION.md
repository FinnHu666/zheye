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
