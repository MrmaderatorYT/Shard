package com.ccs.shard.core;

import java.net.URI;

/** Non-secret, persistable settings for one HTTPS Git remote. */
public final class GitSyncConfig {

    private final String remoteUrl;
    private final String branch;
    private final String username;
    private final String authorName;
    private final String authorEmail;

    public GitSyncConfig(String remoteUrl, String branch, String username,
                         String authorName, String authorEmail) {
        this.remoteUrl = clean(remoteUrl);
        this.branch = valueOr(branch, "main");
        this.username = clean(username);
        this.authorName = valueOr(authorName, "Shard");
        this.authorEmail = valueOr(authorEmail, "shard@localhost");
    }

    public String getRemoteUrl() { return remoteUrl; }

    public String getBranch() { return branch; }

    public String getUsername() { return username; }

    public String getAuthorName() { return authorName; }

    public String getAuthorEmail() { return authorEmail; }

    /** SSH is deliberately excluded until host-key verification has a proper UI. */
    public boolean isValid() {
        try {
            URI uri = URI.create(remoteUrl);
            String scheme = uri.getScheme();
            boolean https = "https".equalsIgnoreCase(scheme);
            return https && uri.getHost() != null && uri.getUserInfo() == null
                    && validBranch(branch) && authorEmail.contains("@");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean validBranch(String value) {
        return value.matches("[A-Za-z0-9._/-]+") && !value.startsWith("/")
                && !value.endsWith("/") && !value.endsWith(".")
                && !value.contains("..") && !value.contains("//")
                && !value.contains("@{");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String valueOr(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
