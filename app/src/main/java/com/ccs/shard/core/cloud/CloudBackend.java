package com.ccs.shard.core.cloud;

import java.io.File;

/**
 * One remote service, seen through the narrowest interface the sync core needs.
 *
 * <p>Deliberately provider-agnostic: no Drive ids, no Dropbox paths, no OAuth
 * in the signatures. Folder creation, id caching and path escaping are the
 * implementation's problem, because Drive addresses directories by id while
 * Dropbox addresses them by path.
 *
 * <p>Every method blocks on network. Call from {@link CloudSyncService}, which
 * runs off both the main and the serial disk executor.
 */
public interface CloudBackend {

    CloudProvider provider();

    /**
     * The signed-in account, for display in settings. Never used as a key.
     * May be {@code null} when the provider does not expose it.
     */
    String accountLabel();

    /**
     * Verifies credentials and makes sure the vault's remote root folder
     * exists, creating it when missing.
     *
     * @throws CloudException with {@link CloudException.Reason#AUTHENTICATION}
     *         when the user has to sign in again.
     */
    void connect() throws CloudException;

    /**
     * Creates or overwrites {@code relPath} remotely from {@code local}.
     *
     * @param previous the entry from a prior upload of this path, or
     *        {@code null} when the file has never been uploaded. When it is
     *        non-null the implementation should update that remote file in
     *        place rather than creating a duplicate; if the remote file has
     *        since been deleted it must fall back to creating a new one rather
     *        than throwing {@link CloudException.Reason#NOT_FOUND}.
     * @return the entry to record in the {@link CloudIndex}.
     */
    CloudEntry upload(String relPath, File local, CloudEntry previous) throws CloudException;

    /**
     * Removes {@code relPath} remotely. A file that is already gone is a
     * success, not a {@link CloudException.Reason#NOT_FOUND}: the queue's job
     * is to reach the desired state, not to assert history.
     */
    void remove(String relPath, CloudEntry previous) throws CloudException;

    /** Drops cached state (folder ids, tokens). Called on disconnect. */
    void close();
}
