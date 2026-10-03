alter table collection_entries drop constraint ck_collection_entry_payload;
alter table collection_entries add constraint ck_collection_entry_payload check (
    (kind='NOTE' and body is not null and post_id is null and external_url is null) or
    (kind='POST' and body is null and external_url is null) or
    (kind='LINK' and title is not null and external_url is not null and post_id is null and body is null)
);
