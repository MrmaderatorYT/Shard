package com.ccs.shard.core.cloud;

/**
 * What the remote side knows about one vault file.
 *
 * <p>Immutable. {@code remoteId} is the provider's handle for the file: a file
 * id on Google Drive, a path-or-id on Dropbox. {@code rev} is the provider's
 * own version marker, kept so a later two-way sync can tell "the remote copy
 * is the one we uploaded" from "somebody else edited it".
 */
public final class CloudEntry {

    private final CloudProvider provider;
    private final String remoteId;
    private final String rev;
    private final long localModified;
    private final long localSize;

    public CloudEntry(CloudProvider provider, String remoteId, String rev,
                      long localModified, long localSize) {
        this.provider = provider == null ? CloudProvider.NONE : provider;
        this.remoteId = remoteId == null ? "" : remoteId;
        this.rev = rev == null ? "" : rev;
        this.localModified = localModified;
        this.localSize = localSize;
    }

    public CloudProvider provider() { return provider; }

    public String remoteId() { return remoteId; }

    public String rev() { return rev; }

    /** Local mtime at the moment of the upload that produced this entry. */
    public long localModified() { return localModified; }

    /** Local size at the moment of the upload that produced this entry. */
    public long localSize() { return localSize; }

    public boolean hasRemoteId() { return !remoteId.isEmpty(); }

    /** Same provider and a usable remote handle. */
    public boolean usableFor(CloudProvider target) {
        return provider == target && hasRemoteId();
    }
}
