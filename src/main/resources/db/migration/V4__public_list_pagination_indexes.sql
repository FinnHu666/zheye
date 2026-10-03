create index idx_post_created on content_posts(created_at desc, id desc);
create index idx_collection_updated on community_collections(updated_at desc, id desc);
