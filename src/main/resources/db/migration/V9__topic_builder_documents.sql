alter table community_collections add column editor_type varchar(16) not null default 'OUTLINE';
alter table community_collections add constraint ck_collection_editor_type check (editor_type in ('OUTLINE','BUILDER'));

create table collection_builder_documents (
    collection_id bigint primary key references community_collections(id) on delete cascade,
    schema_version integer not null,
    document text not null
);
