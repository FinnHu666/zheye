-- FINNS 0.7.0 FINNS-15：邮箱验证、邮件找回与会话撤销。全部为附加变更；旧程序包忽略新列/新表继续运行。

alter table user_accounts add column email_verified_at timestamp with time zone;
alter table user_accounts add column legacy_account boolean not null default false;
alter table user_accounts add column session_epoch bigint not null default 0;
alter table user_accounts add column password_changed_at timestamp with time zone;

-- 迁移时已存在的账号：保持可登录并提示补验证；不批量标记邮箱已验证。
update user_accounts set legacy_account = true;

-- 单行标记：旧账号过渡已执行。H2 开发库没有 Flyway，由 AccountBootstrap 用同一标记幂等补做。
create table account_state (
    id smallint primary key check (id = 1),
    legacy_marked_at timestamp with time zone not null
);
insert into account_state (id, legacy_marked_at) values (1, current_timestamp);

create table account_action_tokens (
    id bigserial primary key,
    purpose varchar(10) not null check (purpose in ('SIGNUP', 'VERIFY', 'RESET')),
    email varchar(120) not null,
    user_id bigint references user_accounts(id) on delete cascade,
    token_hash varchar(64) not null unique,
    status varchar(10) not null check (status in ('PENDING', 'ACTIVE', 'VERIFIED', 'USED', 'SUPERSEDED', 'FAILED')),
    created_at timestamp with time zone not null,
    sent_at timestamp with time zone,
    expires_at timestamp with time zone not null,
    verified_at timestamp with time zone,
    used_at timestamp with time zone
);
create index idx_action_tokens_lookup on account_action_tokens (email, purpose, created_at desc);
create index idx_action_tokens_user on account_action_tokens (user_id, purpose, status);
create unique index ux_action_tokens_one_pending on account_action_tokens (email, purpose) where status = 'PENDING';
create unique index ux_action_tokens_one_live on account_action_tokens (email, purpose) where status in ('ACTIVE', 'VERIFIED');
