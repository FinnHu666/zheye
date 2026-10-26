alter table community_collections add column wizard_metadata text;
alter table community_collections add column scheduled_at timestamp with time zone;
alter table community_collections add column scheduled_document text;
alter table community_collections add column scheduled_visibility varchar(12);
alter table community_collections add column scheduled_ready boolean;
alter table collection_publications add column category varchar(24);
create index idx_collection_publication_category on collection_publications(category,updated_at);
create table collection_covers (
    id varchar(36) primary key,
    collection_id bigint not null references community_collections(id) on delete cascade,
    image_bytes bytea not null,
    removed boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp
);
