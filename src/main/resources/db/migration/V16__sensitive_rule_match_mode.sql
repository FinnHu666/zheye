-- FINNS 0.7.0 FINNS-17 需规修订5（REVIEW-P07）：规则可选整词匹配。附加变更，已有规则默认子串匹配，行为不变。
alter table sensitive_rules add column match_mode varchar(10) not null default 'SUBSTRING' check (match_mode in ('SUBSTRING', 'WHOLE_WORD'));
