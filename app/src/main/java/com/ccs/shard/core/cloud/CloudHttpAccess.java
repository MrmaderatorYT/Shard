package com.ccs.shard.core.cloud;

import java.io.File;
import java.util.Map;

/**
 * The HTTP entry point backends use: {@link CloudHttp} plus one retry after a
 * token expires.
 *
 * <p>Access tokens live about an hour, and a background drain of a large queue
 * will straddle that boundary. Rather than have every backend remember to
 * catch {@link CloudException.Reason#AUTHENTICATION}, refresh and re-issue, it
 * happens once here - and exactly once per call, so revoked consent surfaces as
 * a real auth error instead of an infinite loop.
 */
public final class CloudHttpAccess {

    private CloudHttpAccess() {}

    public static CloudResponse request(String method, String url, CloudTokenSource auth,
                                             Map<String, String> headers, byte[] body,
                                             String contentType) throws CloudException {
        try {
            return CloudHttp.request(method, url, auth.token(), headers, body, contentType);
        } catch (CloudException e) {
            if (e.reason() != CloudException.Reason.AUTHENTICATION) throw e;
            return CloudHttp.request(method, url, auth.refresh(), headers, body, contentType);
        }
    }

    public static CloudResponse upload(String method, String url, CloudTokenSource auth,
                                            Map<String, String> headers, File file,
                                            String contentType) throws CloudException {
        try {
            return CloudHttp.upload(method, url, auth.token(), headers, file, contentType);
        } catch (CloudException e) {
            if (e.reason() != CloudException.Reason.AUTHENTICATION) throw e;
            return CloudHttp.upload(method, url, auth.refresh(), headers, file, contentType);
        }
    }

    /**
     * Creates a file and its content in one request.
     *
     * @return the new remote file id.
     */
    public static String multipartUpload(String url, CloudTokenSource auth, String metadataJson,
                                         File file, String contentType) throws CloudException {
        CloudResponse response;
        try {
            response = CloudHttp.multipart(url, auth.token(), metadataJson, file, contentType);
        } catch (CloudException e) {
            if (e.reason() != CloudException.Reason.AUTHENTICATION) throw e;
            response = CloudHttp.multipart(url, auth.refresh(), metadataJson, file, contentType);
        }
        String id = response.json().optString("id", "");
        if (id.isEmpty()) throw new CloudException(CloudException.Reason.REMOTE);
        return id;
    }

    /** Header map for a JSON request body. */
    public static Map<String, String> json() {
        return CloudHttp.headers("Accept", "application/json");
    }
}
