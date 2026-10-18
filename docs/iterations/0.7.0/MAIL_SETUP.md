# FINNS 账号邮件：服务商选择与配置步骤（Brevo + 2000678.xyz）

日期：2026-10-18。依据：[概设 §3.3.1](DESIGN_ACCOUNT.md)、[设计评审 C2/C5](DESIGN_REVIEW_ACCOUNT.md)。用户提供自有域名 `2000678.xyz`，并要求在 Brevo 与 Resend 之间定一个。

**这份文件是操作手册，不是已完成的配置**：没有注册任何账号，没有写入任何凭据，没有发送过真实邮件。账号注册、DNS 修改和密钥由你本人操作；AI 不代为注册，也不应该看到你的密钥。

## 1. 决定：选 Brevo，Resend 作备选

| 项目 | Brevo（选定） | Resend（备选） |
| --- | --- | --- |
| 免费额度（2026-10-18 官方页面） | 免费档永久免费、无需信用卡；账号通过审批后每天最多 300 封（价格页 FAQ） | 每月 3,000 封、每天 100 封、3 个域名（价格页） |
| 本项目用量 | 邮件全部由用户主动触发，远低于每天 80 封 | 同上 |
| 为什么这样选 | 日额度最大，给被滥用时留出更多余量；SMTP 中继是核心功能；日预算可设 240 | 接入最简单、无需审批；日额度较小，日预算只能设 80 |
| 主要风险 | 新账号需通过**发信审批**，可能要几个工作日或被追问用途；免费档邮件是否带 Brevo 页脚、是否影响事务邮件：**未核实** | 日额度小，被滥用时更快耗尽；SMTP 主机/端口/用户名规则**未核实** |

两者都用 SMTP 接入，换服务商只改环境变量。如果 Brevo 审批卡住或被拒，直接改用 Resend，无需改代码；日预算相应改为 80。

## 2. 域名现状（只读 DNS 查询）

2026-10-18 通过公共解析器 `223.5.5.5` 与 `8.8.8.8` 查询 `2000678.xyz`：

- 权威 DNS：`dns29.hichina.com`、`dns30.hichina.com`——**DNS 托管在阿里云（万网）云解析**，记录要在阿里云云解析 DNS 控制台里加。
- 根域名没有 MX 记录，也没有 TXT 记录（包括没有 SPF）；`_dmarc.2000678.xyz` 没有记录。也就是说根域名目前没有任何邮件配置，**不会和已有 SPF 冲突**。

注意：本机默认解析器会把 `2000678.xyz` 解析到保留地址 `198.18.1.135`，像是本机代理的 fake-IP DNS，所以上面的结论都来自直接查询公共解析器。这是查询当天的状态，你改过 DNS 之后需要重新核对。

**发信域名建议：直接认证根域名 `2000678.xyz`，发件人用 `no-reply@2000678.xyz`。** 因为根域名没有其他邮件服务，不会出现“两条 SPF 记录互相覆盖”的问题。如果以后要在这个域名上收邮件（MX），那是另一套记录，不冲突。

风险：`.xyz` 这类低价后缀有时被收件方评分更低（**未验证**），国内邮箱（QQ、163）也可能把境外服务商的邮件放进垃圾箱。所以第 5 步必须用真实邮箱各测一次。

## 3. 你需要做的步骤

### 步骤 1：注册 Brevo（约 10 分钟 + 等待审批）

1. 打开 Brevo 官网，注册免费账号（页面写明免费且无需信用卡）。用你自己的邮箱，完成邮箱验证。
2. 按提示填写账号/公司资料。用途如实写：“社区网站的账号验证、找回密码和密码变更通知邮件，用户主动触发，量很小”。
3. 等待发信审批（Brevo 页面写“通过审批后才能发送”）。审批结果通常发到注册邮箱；未通过前下面的发送会被拒绝。**不要用任何绕过审批的办法。**

### 步骤 2：在 Brevo 添加并认证发信域名

1. 在 Brevo 控制台找到“发件人、域名和专用 IP（Senders, Domains & Dedicated IPs）”下的“域名（Domains）”，添加 `2000678.xyz`。（菜单名称是按我的了解写的，**未核实**，以控制台为准。）
2. Brevo 会显示一组需要添加的 DNS 记录。通常包括：一条域名验证用的 TXT、DKIM 记录（CNAME 或 TXT，一般两条）、DMARC 的 TXT。**具体的主机名和值必须从 Brevo 页面复制，不要凭我写的例子输入。**

### 步骤 3：在阿里云云解析 DNS 添加记录

1. 登录阿里云控制台 → 云解析 DNS → 找到 `2000678.xyz` → 解析设置 → 添加记录。
2. 逐条照抄 Brevo 给的“记录类型 / 主机记录 / 记录值”。阿里云的“主机记录”只填前缀，不带域名本身（例如 Brevo 给 `xxx._domainkey.2000678.xyz` 就只填 `xxx._domainkey`）；TTL 保持默认或 10 分钟。
3. DMARC 记录：主机记录 `_dmarc`，类型 TXT。起步用 `v=DMARC1; p=none; rua=mailto:你的邮箱`（只监控、不拦截），稳定几周后再考虑收紧。如果 Brevo 已提供 DMARC 记录，以它为准。
4. 不要新建第二条 SPF 记录：根域名目前没有 SPF，Brevo 要求的 SPF（如果有）只加这一条。
5. 回到 Brevo 点“认证/验证（Authenticate）”。DNS 生效可能需要几分钟到几小时。

### 步骤 4：创建 SMTP 密钥并设置 FINNS 的环境变量

1. 在 Brevo 的“SMTP 与 API”页面，记下 SMTP 服务器、端口、登录名，并生成一个 **SMTP 密钥**。我了解的值是服务器 `smtp-relay.brevo.com`、端口 `587`（STARTTLS），**未核实**，以控制台显示为准。这个密钥相当于密码，不要发给任何人（包括 AI）、不要写进仓库或文档。
2. 在 Brevo 里创建发件人 `no-reply@2000678.xyz`（域名认证通过后即可）。
3. 把配置写成你自己的**用户环境变量**（在你自己的 PowerShell 里执行，密钥在提示时输入，不会出现在屏幕上或命令历史里）：

```powershell
$key = Read-Host 'Brevo SMTP key' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($key))
[Environment]::SetEnvironmentVariable('FINNS_MAIL_PASSWORD', $plain, 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_MODE', 'smtp', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_HOST', 'smtp-relay.brevo.com', 'User')   # 以控制台为准
[Environment]::SetEnvironmentVariable('FINNS_MAIL_PORT', '587', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_USERNAME', '<Brevo 控制台显示的 SMTP 登录名>', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_FROM', 'FINNS <no-reply@2000678.xyz>', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_STARTTLS', 'true', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_SSL', 'false', 'User')
[Environment]::SetEnvironmentVariable('FINNS_MAIL_DAILY_BUDGET', '240', 'User')            # Brevo 日额度 300 的 80%
```

   新开的终端才会读到这些变量。`FINNS_PUBLIC_BASE_URL` 先不要设：它必须是用户实际访问 FINNS 的地址，而目前站点只监听 `127.0.0.1:8080`，邮件链接只能在本机打开。要让别人也能点邮件里的链接，需要先把站点部署到公网地址，那是另一件事。
4. 如果 Brevo 给的是 465 端口（隐式 TLS），改成 `FINNS_MAIL_PORT=465`、`FINNS_MAIL_SSL=true`、`FINNS_MAIL_STARTTLS=false`。系统不允许在没有 TLS 的情况下发送带账号密码的 SMTP。

### 步骤 5：先在本机预览里用真实邮件测一次

确认上面的用户级变量已设置后，**新开一个 PowerShell**（它会自动带上用户级变量），在项目目录运行：

```powershell
Set-Location E:\code\FINNS\AI-tome
.\scripts\start-account-preview.ps1 -DatabaseName finns_test_070_account_upgrade -RealMail
```

脚本在缺少 `FINNS_MAIL_HOST/FROM/USERNAME/PASSWORD` 时会拒绝启动；预览只监听本机，只连 `finns_test_*` 隔离库，链接前缀固定为 `http://127.0.0.1:8091`。然后：

1. 打开 `http://127.0.0.1:8091/auth/register`，用你自己的 **QQ 邮箱、163 邮箱、Outlook/Gmail 各注册一次**，看邮件是否到达、进收件箱还是垃圾箱、发件人显示是否正常、链接能否打开。
2. 在 Brevo 的发送日志里确认这几封邮件，并记录每天已发数量。
3. 测完停止预览：`scripts\start-account-preview.ps1 -DatabaseName finns_test_070_account_upgrade -Stop`。

把结果（到达/进垃圾箱/页脚品牌/退信）告诉我，我会记录到验收证据里，并据此决定是否需要调整（例如页脚、DMARC 策略或换 Resend）。

## 4. 如果改用 Resend

注册 Resend → 添加域名 `2000678.xyz` → 按其页面往阿里云 DNS 加 SPF/DKIM（及可选 DMARC）记录 → 创建 API Key。SMTP 的主机、端口与用户名规则**我没有核实**，以 Resend 文档为准（我了解的是 `smtp.resend.com`、用户名 `resend`、密码为 API Key，端口含 465/587，需确认）。日预算改为 80。

## 6. 首次真实测试结果（2026-10-18）

Brevo 登录与发信成功，邮件到达 Gmail 但进了垃圾箱；域名 DKIM/DMARC 记录都在；邮件无 Brevo 页脚（一个样本）。详见 [实施记录 §8](IMPLEMENTATION_ACCOUNT.md)。排查和改进步骤：在 Gmail 打开邮件 →“显示原始邮件”，看 SPF、DKIM、DMARC 是否 PASS；把邮件里的链接换成 HTTPS 公网地址后重发对比；在 Gmail 里多次“不是垃圾邮件”只对你自己的邮箱有效，不能代替发信信誉。

## 5. 已在代码里实现的保护（本轮）

- `FINNS_MAIL_SSL`：隐式 TLS（465）支持。同时把 STARTTLS 改为“必须”（服务器不支持时报错，不再悄悄退回明文），两种方式都校验服务器证书主机名；配置了 SMTP 账号却关闭 TLS 时拒绝启动。
- `FINNS_MAIL_DAILY_BUDGET`：全局日发送预算，用尽后新的验证/重置邮件按发送失败处理（公开响应不变、旧链接保持有效）；改密通知不被拒绝但计入；达到 80% 时日志 WARN 一次。默认 0 表示不限制，**真实配置时必须设置**。
- 这两项都有自动化测试；真实 SMTP 服务商仍未验证。
