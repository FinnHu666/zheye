# FINNS-17 示例词库与误报样例（REVIEW-P07，需规修订5）

日期：2026-10-20。用途：让站长在配置真实规则前，能看清检测**会命中什么、不会命中什么、哪里会误伤**。这是需规 REVIEW-D08 留给设计阶段的“可审阅样例”。

- 以下词语仅为中性/常见垃圾信息类示例，**不随迁移写入任何数据库**；站长自行审阅取舍后在“敏感规则”页添加。
- 真实词库的内容和范围由站长决定，本文不声称提供了可用的完整词库。
- 每条“匹配行为”样例都有对应自动化测试，位置见末列（`SensitiveMatcherTests` 为纯匹配器测试，`ContentReviewTests` 为接口集成测试）。

## 一、示例规则（可作为起步参考）

| 词语 | 建议匹配方式 | 说明 |
| --- | --- | --- |
| 演示拦截词 | 子串 | 体验用，仅用于演示命中流程 |
| 测试联系方式 | 子串 | 体验用 |
| 加微信 | 子串 | 常见引流话术（中文端，整词无差别） |
| 代开发票 | 子串 | 常见垃圾信息 |
| 刷单 | 子串 | 常见诈骗话术 |
| spam | 整词 | 英文词：整词可避免 `spammer` 以外的误伤（见下） |
| cat | 整词 | 仅作整词演示：避免命中 `category` / `concat` |

## 二、匹配行为样例（含绕过与边界）

| 编号 | 规则 | 文本 | 结果 | 为什么 | 对应测试 |
| --- | --- | --- | --- | --- | --- |
| M1 | `badword`（子串） | `BadWord` / `ＢＡＤＷＯＲＤ` | 命中 | 大小写、全半角统一 | `caseWidthWhitespaceAndPunctuationVariantsAllMatch` |
| M2 | `badword` | `b a d w o r d`、`BAD⏎WORD` | 命中 | 空白去掉后相同 | 同上 |
| M3 | `badword` | `b.a.d.w.o.r.d`、`bad_word`、`b-a-d,w!o?r…d` | 命中 | 标点和符号去掉后相同（防插符号绕过） | 同上；`punctuationAndWidthVariantsAreBlockedAndEmptyRulesRejected` |
| M4 | `badword` | 夹有零宽字符、变体选择符的 `badword` | 命中 | 零宽/格式字符去掉 | `caseWidthWhitespaceAndPunctuationVariantsAllMatch` |
| M5 | `badword` | `bad world` | 不命中 | 去掉空白后是 `badworld` | 同上 |
| M6 | `演示拦截词` | `「演 示，拦·截 词」` | 命中 | 中文同理 | `chineseVariantsWithPunctuationMatch` |
| M7 | `演示拦截词` | `演示拦词`（少一个字） | 不命中 | 不做模糊/错别字匹配 | 同上 |
| M8 | `he` `she` `hers` | `ushers she hers` | 三者全命中 | 重叠和包含都会找到 | `overlappingContainedAndRepeatedHitsAreAllReportedOnceInRuleOrder` |
| M9 | `badword` | 标题 `…bad`、正文 `word…` | 不命中 | 各字段单独扫描，不跨字段拼接 | `eachTextIsScannedSeparatelySoMatchesNeverSpanTwoFields` |

## 三、整词匹配样例

| 编号 | 规则 | 文本 | 结果 | 说明 | 对应测试 |
| --- | --- | --- | --- | --- | --- |
| W1 | `cat`（整词） | `the cat sat`、`(cat)`、`cat` | 命中 | 两端是分隔或文本边界 | `wholeWordOnlyConstrainsLatinEndsAndUsesOriginalSeparators` |
| W2 | `cat`（整词） | `category`、`concat` | 不命中 | 英文词内部不算 | 同上 |
| W3 | `cat`（整词） | `a cat, category` | 命中 | 只要有一处独立出现即命中 | 同上 |
| W4 | `cat`（整词）→ 改回子串 | `category` | 整词不命中；子串命中 | 站长可随时切换，新模式只影响后续提交 | `wholeWordRuleOnlyMatchesIndependentLatinWords` |
| W5 | `联系 qq`（整词） | `请联系QQ。` | 命中 | 只约束英文端 | `wholeWordOnlyConstrainsLatinEndsAndUsesOriginalSeparators` |
| W6 | `联系 qq`（整词） | `请联系QQ号` | 不命中 | 英文端后面紧跟中文字符，不是独立词 | 同上 |

## 四、已知的误伤与漏判（需要站长知道）

| 编号 | 场景 | 结果 | 处理办法 |
| --- | --- | --- | --- |
| F1 | 子串规则 `习` 遇到 `学习` | **误命中** | 整词模式对中文无效。请避免单字规则，改用更长的词；仍误伤时靠人工复审兜底。测试：`substringModeKeepsShortWordFalsePositives` |
| F2 | 子串规则 `cat` 遇到 `category` | **误命中** | 把该规则改为整词。同上 |
| F3 | 同义词、谐音、拼音（`jia wei xin`）、繁简转换、错别字 | **漏判** | 不在范围（REVIEW-P07）。需要时逐个加规则，或另提需求评审拼音匹配（会放大误报） |
| F4 | 图片中的文字、附件、链接指向的页面 | **漏判** | 不在范围 |
| F5 | 规则只含标点或空白（如 `……`） | 拒绝保存 | 规范化后为空，无法匹配。测试：`punctuationOnlyPhraseNormalizesToNothing`、`punctuationAndWidthVariantsAreBlockedAndEmptyRulesRejected` |
| F6 | 启用规则为 0 条 | **所有文章/评论/专题发布返回 503** | 上线前必须先启用至少一条规则（REVIEW-P03）。测试：`deletedRuleNoLongerMatchesAndNoEnabledRulesBlocksPublishing` |

## 五、正确性与性能的证据

- 自动机结果与“逐条规则做子串匹配”在 400 组随机输入上逐位一致：`automatonAgreesWithNaiveSubstringMatchingOnRandomInput`。
- 500 条规则、约 12 万字文本，扫描在 2 秒内完成（测试断言上限）：`largeRuleSetStaysFast`；实际量级远低于上限，规则数在 500 以内不需要缓存。
