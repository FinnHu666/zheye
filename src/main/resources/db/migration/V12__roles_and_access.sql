-- FINNS 0.7.0 FINNS-11：角色、权限、用户角色、操作记录与专题可见范围。全部为附加变更，0.6.0 程序包可忽略新表/新列继续运行。

create table roles (
    id bigserial primary key,
    code varchar(40) not null unique,
    name varchar(20) not null,
    description varchar(60) not null default '',
    system_kind varchar(12) check (system_kind in ('ADMIN', 'DEFAULT')),
    sort_order int not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
create unique index ux_roles_name_lower on roles (lower(name));
create unique index ux_roles_system_kind on roles (system_kind) where system_kind is not null;

create table role_permissions (
    role_id bigint not null references roles(id) on delete cascade,
    permission varchar(40) not null,
    primary key (role_id, permission)
);

create table access_control_state (
    id smallint primary key check (id = 1),
    version bigint not null
);
insert into access_control_state (id, version) values (1, 1);

insert into roles (code, name, description, system_kind, sort_order, created_at, updated_at) values
    ('RESTRICTED', '受限成员', '可浏览，暂停发布与互动', null, 10, current_timestamp, current_timestamp),
    ('MEMBER', '普通成员', '注册用户的默认角色', 'DEFAULT', 20, current_timestamp, current_timestamp),
    ('TRUSTED', '受信成员', '可阅读内部受限专题', null, 30, current_timestamp, current_timestamp),
    ('ADMIN', '管理员', '管理用户、角色与权限', 'ADMIN', 1000, current_timestamp, current_timestamp);

insert into role_permissions (role_id, permission)
select r.id, p.permission from roles r
join (values
    ('RESTRICTED', 'content.public'), ('RESTRICTED', 'profile.manage'), ('RESTRICTED', 'selection.manage'),
    ('MEMBER', 'content.public'), ('MEMBER', 'profile.manage'), ('MEMBER', 'selection.manage'), ('MEMBER', 'post.create'),
    ('MEMBER', 'post.interact'), ('MEMBER', 'collection.manage'), ('MEMBER', 'ai.use'),
    ('TRUSTED', 'content.public'), ('TRUSTED', 'profile.manage'), ('TRUSTED', 'selection.manage'), ('TRUSTED', 'post.create'),
    ('TRUSTED', 'post.interact'), ('TRUSTED', 'collection.manage'), ('TRUSTED', 'ai.use'), ('TRUSTED', 'content.restricted'),
    ('ADMIN', 'content.public'), ('ADMIN', 'profile.manage'), ('ADMIN', 'selection.manage'), ('ADMIN', 'post.create'),
    ('ADMIN', 'post.interact'), ('ADMIN', 'collection.manage'), ('ADMIN', 'ai.use'), ('ADMIN', 'content.restricted'),
    ('ADMIN', 'admin.users'), ('ADMIN', 'admin.audit'), ('ADMIN', 'admin.roles')
) as p(code, permission) on p.code = r.code;

-- 现有用户全部设为普通成员（与 0.6.0 登录用户能力一致）；首位管理员由部署配置在启动时指定。
-- role_id 保持可空：回退到 0.6.0 程序包时，旧注册流程不写该列也能继续注册；
-- 0.7.0 把空角色视为注册默认角色，并在下次启动时由 AccessBootstrap 补齐。
alter table user_accounts add column role_id bigint references roles(id);
update user_accounts set role_id = (select id from roles where code = 'MEMBER');
create index idx_user_accounts_role on user_accounts(role_id);

create table access_audit_log (
    id bigserial primary key,
    type varchar(20) not null check (type in ('ROLE_ASSIGN', 'ROLE_PERMISSION', 'ROLE_DEFINE')),
    action varchar(10) check (action in ('CREATE', 'UPDATE', 'DELETE')),
    operator_id bigint references user_accounts(id) on delete set null,
    operator_name varchar(40) not null,
    target_user_id bigint references user_accounts(id) on delete set null,
    target_user_name varchar(40),
    role_id bigint,
    role_code varchar(40),
    role_name varchar(20),
    before_role_code varchar(40),
    before_role_name varchar(20),
    after_role_code varchar(40),
    after_role_name varchar(20),
    copy_from_code varchar(40),
    copy_from_name varchar(20),
    move_to_code varchar(40),
    move_to_name varchar(20),
    added_permissions varchar(500),
    removed_permissions varchar(500),
    moved_count int,
    moved_names varchar(500),
    note varchar(200) not null default '',
    created_at timestamp with time zone not null
);
create index idx_audit_created on access_audit_log(created_at desc, id desc);
create index idx_audit_type_created on access_audit_log(type, created_at desc);

alter table community_collections add column visibility varchar(12) not null default 'PUBLIC' check (visibility in ('PUBLIC', 'RESTRICTED'));
alter table collection_publications add column visibility varchar(12) not null default 'PUBLIC' check (visibility in ('PUBLIC', 'RESTRICTED'));
