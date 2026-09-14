package com.ccs.shard.core.cloud;

/**
 * The remote services Shard can mirror the vault to.
 *
 * <p>The stored {@link #id()} is what lands in preferences and in the cloud
 * index, so it must stay stable across releases even if the enum constants are
 * reordered or renamed. Adding a provider means adding a constant here plus a
 * {@link CloudBackend} implementation; nothing else in the sync core is
 * provider-aware.
 */
public enum CloudProvider {

    /** No cloud mirror configured. The default. */
    NONE("none"),

    /** Google Drive, via the REST v3 API. */
    GOOGLE_DRIVE("gdrive"),

    /** Dropbox. Reserved: no backend implementation yet. */
    DROPBOX("dropbox");

    private final String id;

    CloudProvider(String id) {
        this.id = id;
    }

    /** Stable identifier used for persistence. Never localise or reuse these. */
    public String id() {
        return id;
    }

    /** True once a {@link CloudBackend} exists for this provider. */
    public boolean implemented() {
        return this == GOOGLE_DRIVE;
    }

    /** Parses a persisted {@link #id()}, falling back to {@link #NONE}. */
    public static CloudProvider fromId(String id) {
        if (id != null) {
            for (CloudProvider provider : values()) {
                if (provider.id.equals(id)) return provider;
            }
        }
        return NONE;
    }
}
