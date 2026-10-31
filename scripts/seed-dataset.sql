-- Generates a large dataset for performance experiments. Run it by hand:
--
--   docker compose exec -T postgres psql -U blog -d blog -f /scripts/seed-dataset.sql
--
-- Override any size with -v, for example the SIS #1 target of 5 million posts
-- (needs Docker storage moved to D:, see the README):
--
--   docker compose exec -T postgres psql -U blog -d blog -v posts=5000000 -f /scripts/seed-dataset.sql
--
-- The script adds rows on every run. Reset first with: docker compose down -v

\set ON_ERROR_STOP on

\if :{?users}
\else
\set users 1000
\endif
\if :{?posts}
\else
\set posts 1000000
\endif
\if :{?comments}
\else
\set comments 3000000
\endif
\if :{?post_chars}
\else
\set post_chars 1000
\endif
\if :{?comment_chars}
\else
\set comment_chars 200
\endif

\timing on

-- A fixed seed produces the same dataset shape on every run.
SELECT setseed(0.42);

INSERT INTO users (username, email)
SELECT 'seed_user_' || g, 'seed_user_' || g || '@example.test'
FROM generate_series(1, :users) AS g
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE seed_users AS
SELECT row_number() OVER (ORDER BY id) AS rn, id FROM users;
ANALYZE seed_users;
SELECT count(*) AS user_count FROM seed_users \gset

-- Squaring random() skews rows toward low rn values: some authors are popular.
WITH source AS MATERIALIZED (
    SELECT g,
           1 + floor(power(random(), 2) * :user_count)::BIGINT AS user_rn,
           now() - random() * INTERVAL '365 days' AS ts
    FROM generate_series(1, :posts) AS g
)
INSERT INTO posts (author_id, title, content, created_at, updated_at)
SELECT u.id,
       'Seed post ' || s.g,
       left(repeat(md5(s.g::TEXT), :post_chars / 32 + 1), :post_chars),
       s.ts,
       s.ts
FROM source s
JOIN seed_users u ON u.rn = s.user_rn;

CREATE TEMP TABLE seed_posts AS
SELECT row_number() OVER (ORDER BY id) AS rn, id, created_at FROM posts;
ANALYZE seed_posts;
SELECT count(*) AS post_count FROM seed_posts \gset

-- Cubing random() gives a few posts many comments, so GetComments has deep pages.
WITH source AS MATERIALIZED (
    SELECT g,
           1 + floor(power(random(), 2) * :user_count)::BIGINT AS user_rn,
           1 + floor(power(random(), 3) * :post_count)::BIGINT AS post_rn,
           random() AS age
    FROM generate_series(1, :comments) AS g
)
INSERT INTO comments (post_id, user_id, content, created_at, updated_at)
SELECT p.id,
       u.id,
       left(repeat(md5(s.g::TEXT), :comment_chars / 32 + 1), :comment_chars),
       p.created_at + s.age * (now() - p.created_at),
       p.created_at + s.age * (now() - p.created_at)
FROM source s
JOIN seed_users u ON u.rn = s.user_rn
JOIN seed_posts p ON p.rn = s.post_rn;

ANALYZE users;
ANALYZE posts;
ANALYZE comments;

SELECT (SELECT count(*) FROM users)    AS users,
       (SELECT count(*) FROM posts)    AS posts,
       (SELECT count(*) FROM comments) AS comments,
       pg_size_pretty(pg_database_size(current_database())) AS database_size;
