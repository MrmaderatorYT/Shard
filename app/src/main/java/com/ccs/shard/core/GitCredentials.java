package com.ccs.shard.core;

/** Ephemeral HTTP credentials. Instances are never persisted by Shard. */
public final class GitCredentials {

    private final String username;
    private final String token;

    public GitCredentials(String username, String token) {
        this.username = username == null ? "" : username.trim();
        this.token = token == null ? "" : token;
    }

    public String getUsername() { return username; }

    public String getToken() { return token; }

    public boolean isEmpty() { return token.isEmpty(); }
}
