-- B8 演练用的虚构数据（V18 之后的形态）：话题及其评论、评论审核留痕、专题引用、冻结快照。仅用于隔离测试库。
-- 在 V18 演练库的基础上追加（已有：作者甲/乙、4 篇文章/话题、草稿等）。
insert into content_posts (type, title, summary, body, author_id, author_name, created_at, likes, moderation_state)
select 'TOPIC', t.title, '话题摘要', '话题正文', u.id, '作者乙', now() - interval '2 day', t.likes, t.ms
from user_accounts u, (values ('话题甲-有评论', 3, 'VISIBLE'), ('话题乙-无评论', 1, 'VISIBLE'), ('话题丙-已下架', 0, 'TAKEN_DOWN')) t(title, likes, ms)
where u.email = 'rehearsal-b@example.test';

-- 评论：话题甲两条（一条有审核流水与待审任务）、一篇文章下一条（必须保留）
insert into comments (post_id, author_id, author_name, body, created_at)
select p.id, u.id, '作者甲', c.body, now() from content_posts p, user_accounts u, (values ('话题甲-有评论', '评论一'), ('话题甲-有评论', '评论二'), ('甲的文章一', '文章下的评论')) c(t, body)
where p.title = c.t and u.email = 'rehearsal-a@example.test';

-- 评论审核状态：话题甲评论一（待审任务 + 流转 + 回执 + noted）；文章评论（保留）
insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select u.id, 'COMMENT', 'COMMENT:' || p.id || ':ck1', c.id, 1, 1, false, false, now(), now()
from content_posts p join comments c on c.post_id = p.id and c.body = '评论一', user_accounts u
where p.title = '话题甲-有评论' and u.email = 'rehearsal-a@example.test';
insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select u.id, 'COMMENT', 'COMMENT:' || p.id || ':ck2', c.id, 1, 1, true, false, now(), now()
from content_posts p join comments c on c.post_id = p.id and c.body = '文章下的评论', user_accounts u
where p.title = '甲的文章一' and u.email = 'rehearsal-a@example.test';
-- 键只有前缀、尚无 target_id 的在途评论（按 key 前缀匹配的路径）
insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select u.id, 'COMMENT', 'COMMENT:' || p.id || ':pending-only', null, 1, 1, false, false, now(), now()
from content_posts p, user_accounts u where p.title = '话题甲-有评论' and u.email = 'rehearsal-a@example.test';

insert into review_submissions (state_id, author_id, content_type, title, generation, snapshot_json, snapshot_version, triggers, attempts, status, created_at)
select s.id, s.author_id, 'COMMENT', '评论一', 1, '{"type":"COMMENT","body":"评论一"}', 1, 'RULE', 1, 'PENDING', now() from review_content_state s where s.content_key like 'COMMENT:%:ck1';
insert into review_receipts (state_id, request_id, response_json, created_at)
select s.id, 'req-1', '{}', now() from review_content_state s where s.content_key like 'COMMENT:%:ck1';
insert into review_noted (content_type, content_id, author_id, title, words, created_at)
select 'COMMENT', c.id, c.author_id, '评论一', '词', now() from comments c where c.body = '评论一'
union all select 'COMMENT', c.id, c.author_id, '文章下的评论', '词', now() from comments c where c.body = '文章下的评论';
insert into review_audit_log (type, outcome, content_type, content_id, created_at)
select 'SUBMIT', 'SUCCESS', 'COMMENT', c.id, now() from comments c where c.body = '评论一';

-- 专题：一个已发布专题引用话题甲（条目）与文章一；旧 collection_posts 也挂一条话题
insert into community_collections (name, description, owner_name, item_count, created_at, owner_id, status)
select '演练专题', '含话题引用', '作者甲', 2, now(), u.id, 'PUBLISHED' from user_accounts u where u.email = 'rehearsal-a@example.test';
insert into collection_sections (collection_id, title, sort_order) select id, '一节', 0 from community_collections where name = '演练专题';
insert into collection_entries (collection_id, section_id, kind, sort_order, post_id, annotation)
select c.id, s.id, 'POST', v.o, p.id, '' from community_collections c join collection_sections s on s.collection_id = c.id,
  (values ('话题甲-有评论', 0), ('甲的文章一', 1)) v(t, o) join content_posts p on p.title = v.t where c.name = '演练专题';
insert into collection_posts (collection_id, post_id, created_at)
select c.id, p.id, now() from community_collections c, content_posts p where c.name = '演练专题' and p.title = '话题乙-无评论';
insert into collection_publications (collection_id, name, description, owner_name, item_count, schema_version, document, updated_at)
select c.id, c.name, c.description, c.owner_name, 2, 1, '{"sections":[{"entries":[{"id":1,"kind":"POST","postId":' || (select id from content_posts where title='话题甲-有评论') || ',"postTitle":"话题甲-有评论"}]}]}', now() from community_collections c where c.name = '演练专题';
