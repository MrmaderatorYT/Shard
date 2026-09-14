package com.ccs.shard.core.cloud;

/**
 * What one drain of the cloud queue achieved. A mutable field bag, matching
 * {@link com.ccs.shard.core.GitSyncResult}.
 */
public final class CloudSyncResult {

    public int uploaded;
    public int deleted;
    public int failed;
    /** Operations still queued when the drain stopped. */
    public int remaining;
    /** The reason of the last failure, or {@code null}. */
    public CloudException.Reason lastError;

    public boolean changed() {
        return uploaded > 0 || deleted > 0;
    }

    /** True when the drain should be retried later. */
    public boolean shouldRetry() {
        return remaining > 0 && (lastError == null
                || lastError == CloudException.Reason.NETWORK
                || lastError == CloudException.Reason.RATE_LIMIT
                || lastError == CloudException.Reason.REMOTE);
    }
}
