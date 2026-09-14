package com.ccs.shard.core;

/** A safe, UI-facing Git failure category; credentials never enter its message. */
public final class GitSyncException extends Exception {

    public enum Reason { AUTHENTICATION, NETWORK, BRANCH, CONFLICT, REPOSITORY }

    private final Reason reason;

    public GitSyncException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public GitSyncException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
