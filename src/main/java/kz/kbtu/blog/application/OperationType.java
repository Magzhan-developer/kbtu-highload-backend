package kz.kbtu.blog.application;

/** Part of an idempotency key's scope: one key can be used once per user per operation. */
enum OperationType {
    CREATE_POST,
    CREATE_COMMENT
}
