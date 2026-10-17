create table user_ai_credentials (
    user_id bigint primary key references user_accounts(id) on delete cascade,
    ciphertext varchar(1024) not null,
    nonce varchar(32) not null,
    last_four varchar(4) not null,
    updated_at timestamp with time zone not null
);
create table user_module_favorites (
    id varchar(36) primary key,
    user_id bigint not null references user_accounts(id) on delete cascade,
    block text not null,
    created_at timestamp with time zone not null
);
create index idx_module_favorites_user_created on user_module_favorites(user_id, created_at desc, id);
