package com.ccs.shard.core.cloud;

/**
 * What the cloud queue owes the remote for one path.
 *
 * <p>There is no MOVE: a rename enqueues a {@link #DELETE} of the old path and
 * an {@link #UPSERT} of the new one. That costs one extra request but keeps
 * every provider's implementation to two verbs, and makes the queue safely
 * collapsible - the last op recorded for a path is the only one that matters.
 */
public enum CloudOpKind {

    UPSERT("u"),
    DELETE("d");

    private final String id;

    CloudOpKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CloudOpKind fromId(String id) {
        if (UPSERT.id.equals(id)) return UPSERT;
        if (DELETE.id.equals(id)) return DELETE;
        return null;
    }
}
