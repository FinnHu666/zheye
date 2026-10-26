# FINNS 0.8.0｜首轮代码审查与可复现基线产物

日期：2026-10-25；内部追踪：FINNS-26；阶段：G1需求评审，未通过。以下本轮生成材料为脱敏本地产物；不包含真实扫描结果、秘密或完整远程响应。

| 产物 | 路径 | 状态/用途 |
| --- | --- | --- |
| 需求 | [需求修订1](REQUIREMENTS_CODE_REVIEW_BASELINE.md) | 作者已吸收一轮意见；未再次独立复核，待用户决定/确认 |
| 评审 | [第1轮评审](REQUIREMENT_REVIEW_CODE_REVIEW_BASELINE.md) | 对D1带条件通过；9维度、12项独立事实抽查、I1–I3及S1/S2去向；不代表安全审计 |
| 不变输入 | [D1快照](REQUIREMENTS_CODE_REVIEW_BASELINE_D1.md) | 评审前输入，SHA-256 `97c17297333b8aa4a9abe89708353795aeffdd4805ce10f2a0204b53b8decfef`；后续不改写 |
| 阶段 | [进度入口](../../delivery-progress.json) | 仅本项对象增量，G1 pending；其他任务/顶层元数据语义保持 |
| 交接 | [AI交接](../../AI_HANDOFF.md) | 当前决定、未验与下一步；头部增量保留其他会话内容 |

历史/既有输入为[FINNS-25已确认需规](REQUIREMENTS_CODE_STANDARDS.md)、[工程基线决定](../../requirements/REPO_BASELINE_DECISIONS.md)与相关源码；本项未改写它们。当前分支`release/0.7.0`，需求准备时HEAD `901047127ec3e9a92dad1cb5096a0acab1c4bcdf`、存在其他未提交改动；该事实不构成冻结的执行基线。

## 本轮验证

- 实际结果：4个本项Markdown文件的20条本地链接全部可达，无行尾空白；D1字节哈希保持不变，本项登记的需求/评审哈希一致，进度JSON可解析。共享记录范围的`git diff --check -- CHANGELOG.md docs/AI_HANDOFF.md docs/delivery-progress.json`退出0；Git提示既有CRLF未来会转LF，本轮未执行换行转换或重新规范化。
- 进度命令：`python -B -X utf8 .agents/skills/finns-delivery-workflow/scripts/check_progress.py --root . --state docs/delivery-progress.json`。`-B`避免生成pyc；实际退出2，无配置错误，本项仍在G1待确认；不是业务通过。其他任务阶段按当前文件保留，不替其补证或批准。
- 仅文档变更检查相应文件的空白/链接，不对既有全工作树问题作清理，不批量刷新其他任务证据哈希。
- 未执行：实际代码审计、密钥/漏洞扫描、许可证核验、测试/构建、浏览器、运行服务、数据库、工具安装、Git提交/推送、发布或Plane写入。

下一步：用户对修订1及U1–U3决定/延期、U4选择/延期作明确答复；G1通过后提出本项Plane正文当前→目标差异并确认，不进入实际扫描/开发。具体规则工具/运行入口与归档阈值留AUD-L01–L04在G3/G4交付。
