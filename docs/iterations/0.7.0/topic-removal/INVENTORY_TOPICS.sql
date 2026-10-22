-- FINNS-18 B8：话题删除前的盘点（只读）。契约 §2.2、设计 §8 / §14.3。
-- 只在【备份副本】上运行；结果交用户按类确认后，才可能进入 V19。本脚本不修改任何数据。
-- 用法：psql -v ON_ERROR_STOP=1 -f INVENTORY_TOPICS.sql <备份副本库>
begin read only;

\echo '== 1 话题总览（含作者数、可见性、点赞合计）'
select count(*) as topics,
       count(distinct author_id) as authors,
       count(*) filter (where moderation_state = 'VISIBLE') as visible,
       count(*) filter (where moderation_state <> 'VISIBLE') as not_visible,
       coalesce(sum(likes), 0) as likes_lost,
       min(created_at) as oldest, max(created_at) as newest
from content_posts where type = 'TOPIC';

\echo '== 2 话题下的评论（将随话题级联删除，不可恢复）'
select count(*) as comments,
       count(distinct c.author_id) as comment_authors,
       count(*) filter (where c.moderation_state <> 'VISIBLE') as comments_not_visible
from comments c join content_posts p on p.id = c.post_id where p.type = 'TOPIC';

\echo '== 3 这些评论的审核留痕（V19 会一并清除，审计日志保留）'
select
  (select count(*) from review_content_state s where s.content_type = 'COMMENT' and (
        s.target_id in (select c.id from comments c join content_posts p on p.id = c.post_id where p.type = 'TOPIC')
     or exists (select 1 from content_posts p where p.type = 'TOPIC' and s.content_key like 'COMMENT:' || p.id || ':%'))) as comment_states,
  (select count(*) from review_noted n where n.content_type = 'COMMENT' and n.content_id in
        (select c.id from comments c join content_posts p on p.id = c.post_id where p.type = 'TOPIC')) as noted_rows,
  (select count(*) from review_submissions r join review_content_state s on s.id = r.state_id
     where s.content_type = 'COMMENT' and r.status = 'PENDING' and (
        s.target_id in (select c.id from comments c join content_posts p on p.id = c.post_id where p.type = 'TOPIC')
     or exists (select 1 from content_posts p where p.type = 'TOPIC' and s.content_key like 'COMMENT:' || p.id || ':%'))) as pending_comment_tasks;

\echo '== 4 被专题引用（删除后 collection_entries.post_id 置空，显示“引用不可用”；旧 collection_posts 行被删）'
select count(*) as entry_refs, count(distinct e.collection_id) as collections_affected,
       count(distinct e.collection_id) filter (where c.status = 'PUBLISHED') as published_collections_affected
from collection_entries e join content_posts p on p.id = e.post_id join community_collections c on c.id = e.collection_id
where p.type = 'TOPIC';
select count(*) as legacy_collection_posts from collection_posts cp join content_posts p on p.id = cp.post_id where p.type = 'TOPIC';

\echo '== 5 已发布专题的冻结快照里提到话题的数量（仅信息：V19 不改快照；公开读取时 CollectionPublicationService.read 会把不再可见/已删除的文章遮成“原文已不可用”，旧标题不会展示）'
select count(*) as published_snapshots_mentioning_topics
from collection_publications cp
where exists (select 1 from content_posts p where p.type = 'TOPIC' and cp.document ~ ('"postId":' || p.id || '[,}]'));

\echo '== 6 真实内容提示：作者清单（演示账号以外的作者需要再次确认）'
select u.id, u.email, count(*) as topics
from content_posts p join user_accounts u on u.id = p.author_id
where p.type = 'TOPIC' group by u.id, u.email order by topics desc, u.id limit 50;

\echo '== 7 其他可能指向话题的数据（只列数量）'
select (select count(*) from review_audit_log a where a.content_type = 'COMMENT' and a.content_id in
          (select c.id from comments c join content_posts p on p.id = c.post_id where p.type = 'TOPIC')) as audit_rows_kept_as_metadata,
       (select count(*) from article_drafts d join content_posts p on p.id = d.post_id where p.type = 'TOPIC') as drafts_pointing_at_topics_should_be_0;

\echo '== 8 迁移后应为 0 的文章旧键检查（V18 回填遗留）'
select count(*) as article_states_with_old_key from review_content_state where content_type = 'ARTICLE' and content_key not like 'ARTICLE#%';

rollback;
