CREATE TABLE users (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(50)  NOT NULL UNIQUE,
    email    VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE posts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    author_id  BIGINT       NOT NULL REFERENCES users (id),
    title      VARCHAR(200) NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- Serves the author feed: keyset pagination walks this index directly.
CREATE INDEX idx_posts_author_feed ON posts (author_id, created_at DESC, id DESC);

CREATE TABLE comments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Serves the comment list, and also makes ON DELETE CASCADE fast.
CREATE INDEX idx_comments_post_feed ON comments (post_id, created_at DESC, id DESC);

-- response_body and response_status stay NULL only inside the transaction that
-- claims the key. They are protocol-neutral: neither HTTP nor gRPC specific.
CREATE TABLE idempotency_records (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    operation_type  VARCHAR(32) NOT NULL,
    idempotency_key UUID        NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    response_body   TEXT,
    response_status VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_scope UNIQUE (user_id, operation_type, idempotency_key)
);
