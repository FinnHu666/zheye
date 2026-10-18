-- FINNS 0.7.0 FINNS-16：账号停用状态、唯一站长与扩展的操作记录。全部为附加/放宽变更；旧程序包忽略新列继续运行。
-- 注意：旧程序包不认识 account_status，回退后停用账号会重新可以登录（见 DESIGN_USER_ADMIN.md §4）。

alter table user_accounts add column account_status varchar(10) not null default 'ACTIVE' check (account_status in ('ACTIVE', 'DISABLED'));
alter table user_accounts add column status_version bigint not null default 1;

alter table roles drop constraint if exists roles_system_kind_check;
alter table roles add constraint roles_system_kind_check check (system_kind in ('ADMIN', 'DEFAULT', 'OWNER'));

-- 已有同名自定义角色时给系统角色另取名，避免迁移因 ux_roles_name_lower 失败。
insert into roles (code, name, description, system_kind, sort_order, created_at, updated_at)
select 'OWNER', case when exists (select 1 from roles where lower(name) = lower('站长')) then '站长（系统）' else '站长' end,
       '唯一站点所有者，管理管理员；仅通过专门入口转交', 'OWNER', 1, current_timestamp, current_timestamp
where not exists (select 1 from roles where system_kind = 'OWNER');

insert into role_permissions (role_id, permission)
select r.id, p.permission from roles r
cross join (values ('content.public'), ('profile.manage'), ('selection.manage'), ('post.create'), ('post.interact'), ('collection.manage'),
                   ('ai.use'), ('content.restricted'), ('admin.users'), ('admin.audit'), ('admin.roles')) as p(permission)
where r.system_kind = 'OWNER'
  and not exists (select 1 from role_permissions x where x.role_id = r.id and x.permission = p.permission);

-- 站长不在这里指定：由 AccessBootstrap 在启动时按部署配置邮箱或最早的正常管理者幂等初始化。
alter table access_control_state add column owner_user_id bigint references user_accounts(id) on delete set null;
alter table access_control_state add column owner_version bigint not null default 1;

alter table access_audit_log drop constraint if exists access_audit_log_type_check;
alter table access_audit_log add constraint access_audit_log_type_check
    check (type in ('ROLE_ASSIGN', 'ROLE_PERMISSION', 'ROLE_DEFINE', 'ACCOUNT_STATUS', 'OWNER_TRANSFER'));
alter table access_audit_log drop constraint if exists access_audit_log_action_check;
alter table access_audit_log add constraint access_audit_log_action_check
    check (action in ('CREATE', 'UPDATE', 'DELETE', 'DISABLE', 'RESTORE', 'TRANSFER', 'INIT'));
alter table access_audit_log add column before_status varchar(10);
alter table access_audit_log add column after_status varchar(10);
alter table access_audit_log add column outcome varchar(10) not null default 'SUCCESS' check (outcome in ('SUCCESS', 'REJECTED', 'FAILED'));
alter table access_audit_log add column result_message varchar(200);
