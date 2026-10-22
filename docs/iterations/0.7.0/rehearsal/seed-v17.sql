-- B7 演练用的假数据（V17 形态）。全部为虚构内容，仅用于 finns_test_v18。
insert into user_accounts (email, password_hash, display_name, created_at) values
 ('rehearsal-a@example.test', 'x', '作者甲', now()), ('rehearsal-b@example.test', 'x', '作者乙', now());

-- 作者甲：P1 已发布且有旧键状态；P2 已发布但没有任何审核状态（审核功能上线前发布）；P3 话题；P4 已下架文章（有状态）
insert into content_posts (type, title, summary, body, author_id, author_name, created_at, moderation_state) values
 ('ARTICLE', '甲的文章一', '摘要一', '正文一', (select id from user_accounts where email='rehearsal-a@example.test'), '作者甲', now() - interval '5 day', 'VISIBLE'),
 ('ARTICLE', '甲的文章二（无状态）', '摘要二', '正文二', (select id from user_accounts where email='rehearsal-a@example.test'), '作者甲', now() - interval '9 day', 'VISIBLE'),
 ('TOPIC',   '一个话题', '话题摘要', '话题正文', (select id from user_accounts where email='rehearsal-a@example.test'), '作者甲', now() - interval '3 day', 'VISIBLE'),
 ('ARTICLE', '甲的文章四（已下架）', '摘要四', '正文四', (select id from user_accounts where email='rehearsal-a@example.test'), '作者甲', now() - interval '7 day', 'TAKEN_DOWN');

insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select p.author_id, 'ARTICLE', 'ARTICLE:key-' || p.id, p.id, 1, 1, true, false, now(), now() from content_posts p where p.type='ARTICLE' and p.title in ('甲的文章一', '甲的文章四（已下架）');

-- 作者乙：在途提交（有快照）、缺 title 的快照、三项全空快照、无提交的空状态、超长标题快照
insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select u.id, 'ARTICLE', k, null, 1, 1, false, false, now(), now() from user_accounts u, (values ('ARTICLE:b-inflight'), ('ARTICLE:b-notitle'), ('ARTICLE:b-allempty'), ('ARTICLE:b-nosub'), ('ARTICLE:b-long')) t(k) where u.email='rehearsal-b@example.test';

insert into review_submissions (state_id, author_id, content_type, title, generation, snapshot_json, snapshot_version, triggers, attempts, status, created_at)
select s.id, s.author_id, 'ARTICLE', '', 1, v.j, 1, 'RULE', 1, 'PENDING', now() - interval '1 hour'
from review_content_state s join (values
  ('ARTICLE:b-inflight', '{"type":"ARTICLE","title":"乙的在途稿","summary":"在途摘要","body":"在途正文"}'),
  ('ARTICLE:b-notitle',  '{"type":"ARTICLE","summary":"只有摘要","body":""}'),
  ('ARTICLE:b-allempty', '{"type":"ARTICLE","title":"","summary":"","body":""}'),
  ('ARTICLE:b-long',     '{"type":"ARTICLE","title":"' || repeat('长', 150) || '","summary":"' || repeat('摘', 300) || '","body":"正文"}')
) v(k, j) on s.content_key = v.k and s.content_type='ARTICLE';

-- 作者乙的评论与专题状态不应被迁移触碰
insert into review_content_state (author_id, content_type, content_key, target_id, attempts, generation, approved_once, legacy, created_at, updated_at)
select id, 'COMMENT', 'COMMENT:c1', null, 0, 1, false, false, now(), now() from user_accounts where email='rehearsal-b@example.test';
