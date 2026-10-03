create table data_migration_batches (
    batch_key varchar(120) primary key,
    source_label varchar(260) not null,
    completed_at timestamp with time zone not null,
    row_counts jsonb not null,
    unresolved_owner_collection_ids jsonb not null
);
