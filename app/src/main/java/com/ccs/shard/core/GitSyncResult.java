package com.ccs.shard.core;

/** Value returned by a completed Git synchronization. */
public final class GitSyncResult {

    public int pulled;
    public int pushed;
    public int conflicts;
    public boolean committed;
    public boolean remoteUpdated;
    public String revision = "";
}
