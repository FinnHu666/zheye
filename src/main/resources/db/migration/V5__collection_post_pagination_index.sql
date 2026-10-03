create index idx_collection_posts_page on collection_posts(collection_id, created_at desc, post_id desc);
create index idx_post_author_created on content_posts(author_id, created_at desc, id desc);
