# 0.7.0 规划与交付 skill 验证

日期：2026-10-10。范围：本轮新增/修改文档、skill 与经确认的 Plane 管理写入。应用版本保持 0.6.0；不是 0.7.0 功能验收。

## 1. 检查结果

| 检查 | 方法 | 结果/限制 |
| --- | --- | --- |
| skill 格式 | 用 skill-creator 的 quick_validate.py 分别检查两个项目 skill，PYTHONUTF8=1 | 已通过，两次退出码 0；只证明结构检查 |
| 引用与元数据 | 校验本轮 Markdown 的实际本地链接、YAML 字段与入口 | 已通过：17 份 Markdown、95 个本地链接存在；元数据描述长度及默认 prompt 合法，两个入口均引用新 skill，退出码 0 |
| 状态与边界 | 按下表进行本地情景走查 | 见第 2 节；不是启动独立代理的行为评测 |
| 版本及基线 | 四处版本及 lock 根包核对、需规 0.5 SHA256 对比 | 已通过：均为 0.6.0，需规原文哈希一致；没有更新应用版本 |
| 最终差异 | git diff --check；核对本轮文件范围 | 已通过，退出码 0；只有既有 CRLF/LF 提示；新增文档末尾换行检查通过，既有未提交修改继续保留 |
| Plane P01 | 确认后新增、按 ID 回读归属/标题/状态/描述 | INBOX-2 已创建并回读；目标版本写在描述，Backlog；未创建正式 Release |
| Plane P02 | 最新读取后保留原文追加，再回读 | INBOX-1 已追加并回读；全文及原文前缀经 HTML 包装/空白规范化后匹配，其他管理字段未变；并发限制见同步回执 |

格式检查命令（使用已存在的 D: Python，没有安装依赖）：

```powershell
$env:PYTHONUTF8 = '1'
& 'D:/Program Files/Python/Python3.1.3/python.exe' 'D:/env/.codex/skills/.system/skill-creator/scripts/quick_validate.py' '.agents/skills/finns-delivery-workflow'
& 'D:/Program Files/Python/Python3.1.3/python.exe' 'D:/env/.codex/skills/.system/skill-creator/scripts/quick_validate.py' '.agents/skills/finns-development'
git diff --check
```

本轮引用/元数据/基线检查助手及 JSON 结果在忽略目录 `target/delivery-workflow-proof/check_documents.py`、`document-checks.json`。从项目根目录执行：

```powershell
$env:PYTHONUTF8 = '1'
& 'D:/Program Files/Python/Python3.1.3/python.exe' 'target/delivery-workflow-proof/check_documents.py'
```

助手逐份检查本轮 17 个 Markdown 的本地链接（跳过代码围栏与远程 URL）、文件存在性/末尾换行；解析 openai.yaml 的描述长度与 skill 调用名；解析 VERSION、pom.xml、package/lock 根版本并比较 0.6.0；以 SHA256 核对需规原文。检查输出只保存计数、路径、版本、哈希和错误，不复制正文。忽略目录可被清理；方法与本记录为持久摘要，不声称该助手已经加入 CI。

需规 0.5 原文 SHA256：`508A49258DA9D9AC9082E47C1C3DCFD35874C4CFF41E42CBACB1353A7B261842`。

## 2. 操作边界走查

这是对照实际规则和本轮动作的人工/本地干运行审阅，无远程模拟写入、无业务执行，也不宣称实现了 CI 强制门禁。

| 情景 | 应执行的判断 | 本轮核对结果 |
| --- | --- | --- |
| FINNS 新增多轮沟通到下一版本 | 适用；登记/排期，细节与原型待确认，不改业务 | 本轮已按此执行，版本/需规保留 |
| 普通问题“Session 是什么” | 交付 skill 不触发，不新增迭代包或连接 Plane | 描述与两个入口均限定项目交付/归档任务，普通问答明确排除 |
| 用户要求实现功能，但没有具体原型确认 | 核对已有有效确认；缺失则停在对应门禁并继续可独立规划 | G2 及本轮决定明确未满足，未开始业务实现 |
| 用户已确认原型与实施范围 | 引用确认来源，不重复要求同一批准 | skill 与阶段规范明确复用，不将内部设计检查变成审批 |
| 要求同步 Plane | 先读取对象，给具体差异，每项确认后才写 | P01/P02 分别提问、分别得到明确确认；未扩大为创建项目/Release |
| 写入返回超时、不知道是否创建成功 | 先按标识/ID 查询，禁止盲目重试 | Plane 协议有明确分支；本轮未注入真实超时，不声称故障实测 |
| 文档完成但功能未实施 | 不把业务工作项标 Done，不称上线 | INBOX-2 为 Backlog；本地计划/交接分开记录 |
| AI 日志含 Key/个人资料 | 先脱敏，拒绝归档秘密；不保存隐藏推理 | 产物规则明确禁止，模板不要求完整对话/环境变量或工具响应 |
| 保存过程文件 | 引用既有权威文档，记录新产物和未产生文件；不批量搬移历史资料 | ARTIFACTS 已登记；target/data 的证据寿命明确说明 |

走查证明规则覆盖这些决策分支，不能证明未来 AI 一定遵守；长期效果需在后续真实迭代检验。

## 3. 本轮未执行

没有执行前端构建、Node/Java/数据库业务测试、产品原型修改、浏览器截图/视口验收、数据库访问或迁移、服务启停、收费 AI 请求、发版、Git 提交/推送。原因：本轮是需求安排、流程 skill 与文档任务，没有业务改动。

8080/8082 的运行状态沿用最近交接，未复验，不复用历史 PID 作为当前事实。旧验收和需规原文保留，未倒填新功能结果。实际 Plane 差异与确认、回读见 [同步回执](PLANE_SYNC.md)。
