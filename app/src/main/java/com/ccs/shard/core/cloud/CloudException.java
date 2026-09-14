package com.ccs.shard.core.cloud;

/**
 * A failed remote operation.
 *
 * <p>Follows {@link com.ccs.shard.core.GitSyncException}: the message carries
 * only the reason, never a token, account name or URL, so it is safe to log.
 */
public class CloudException extends Exception {

    public enum Reason {
        /** Not signed in, consent revoked, or the token was rejected. */
        AUTHENTICATION,
        /** No connectivity, timeout, or a 5xx from the provider. */
        NETWORK,
        /** The provider asked us to slow down (429, or a rate-limit 403). */
        RATE_LIMIT,
        /** The account is out of storage. */
        QUOTA,
        /** The remote file is gone; the local copy has to be re-uploaded. */
        NOT_FOUND,
        /** The provider rejected the request for any other reason. */
        REMOTE,
        /** Shard itself is misconfigured (missing OAuth client id, no vault). */
        CONFIGURATION
    }

    private final Reason reason;

    public CloudException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public CloudException(Reason reason, Throwable cause) {
        super(reason.name());
        this.reason = reason;
        if (cause != null) initCause(cause);
    }

    public Reason reason() {
        return reason;
    }

    /**
     * True when the same operation is worth attempting again later. Auth,
     * quota and configuration failures need the user to act first, so a
     * retry loop would only burn battery.
     */
    public boolean retryable() {
        return reason == Reason.NETWORK
                || reason == Reason.RATE_LIMIT
                || reason == Reason.REMOTE;
    }
}
