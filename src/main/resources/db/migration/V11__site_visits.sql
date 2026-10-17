-- FINNS 0.7.0 FINNS-10：全站累计访问次数（单行计数，起点为迁移执行时间）
create table site_visit_counter (
    id smallint primary key check (id = 1),
    total bigint not null default 0 check (total >= 0),
    counted_since timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
insert into site_visit_counter (id, total, counted_since, updated_at)
values (1, 0, current_timestamp, current_timestamp);
