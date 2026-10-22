\echo == drafts-skipped
select 1 where false;
\echo == state keys
select 1 where false;
\echo == assertions (expect all t)
select 'A 已发布文章均有草稿且一一对应' chk, (select count(*) from content_posts where type='ARTICLE') = (select count(*) from article_drafts where post_id is not null) ok
union all select 'B 无旧键残留', not exists (select 1 from review_content_state where content_type='ARTICLE' and content_key not like 'ARTICLE#%')
union all select 'B2 新键均指向同作者草稿', not exists (select 1 from review_content_state s where s.content_type='ARTICLE' and s.content_key like 'ARTICLE#%' and not exists (select 1 from article_drafts d where 'ARTICLE#' || d.id = s.content_key and d.author_id = s.author_id))
union all select 'C 在途缺title快照也建稿(title空,summary有)', exists (select 1 from article_drafts where post_id is null and title='' and summary='只有摘要')
union all select 'C 全空快照被跳过', not exists (select 1 from article_drafts where summary='' and title='' and body='')
union all select 'C 无提交的空状态未建稿且未改键', (select content_key from review_content_state where content_key like '%b-nosub') is not null
union all select 'C 超长快照被截到120/280码点', exists (select 1 from article_drafts where char_length(title)=120 and char_length(summary)=280)
union all select 'D 评论状态未被触碰', (select content_key from review_content_state where content_type='COMMENT') = 'COMMENT:c1'
union all select 'E 话题行未变', (select count(*) from content_posts where type='TOPIC') = 1
union all select 'F 下架文章保持下架', (select moderation_state from content_posts where title like '%已下架%') = 'TAKEN_DOWN'
union all select 'G 辅助列已删除', not exists (select 1 from information_schema.columns where table_name='article_drafts' and column_name='legacy_state_id')
union all select 'H updated_at 存在且旧行为空', (select count(*) from content_posts where updated_at is not null) = 0
union all select 'I 约束已放宽(WITHDRAWN可写)', (select pg_get_constraintdef(oid) like '%WITHDRAWN%' from pg_constraint where conname='content_posts_moderation_state_check')
union all select 'J 审计类型含DELETE_DRAFT', (select pg_get_constraintdef(oid) like '%DELETE_DRAFT%' from pg_constraint where conname='review_audit_log_type_check');
