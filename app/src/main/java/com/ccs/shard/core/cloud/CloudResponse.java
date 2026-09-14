package com.ccs.shard.core.cloud;

import org.json.JSONObject;

/**
 * A successful HTTP response body, as handed to a {@link CloudBackend}.
 *
 * <p>A separate top-level type rather than a nested one because the HTTP
 * client itself stays package-private while backends live in subpackages and
 * need to read what came back.
 */
public final class CloudResponse {

    private final int status;
    private final String body;

    CloudResponse(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** The body as JSON. Throws {@link CloudException.Reason#REMOTE} when it is not. */
    public JSONObject json() throws CloudException {
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            throw new CloudException(CloudException.Reason.REMOTE, t);
        }
    }
}
