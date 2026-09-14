package com.ccs.shard.core.cloud;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The smallest HTTP client that can drive a REST cloud API.
 *
 * <p>Shard ships no HTTP stack, and pulling in {@code google-api-services-drive}
 * for this would add megabytes plus reflection-based JSON that fights R8. Drive
 * and Dropbox are both plain JSON-over-HTTPS, so {@link HttpURLConnection} and
 * {@code org.json} - both already on the platform - cover the whole surface.
 *
 * <p>Every call blocks. Status codes are mapped to {@link CloudException.Reason}
 * once, here, so backends do not each re-invent the classification.
 */
final class CloudHttp {

    private static final String TAG = "CloudHttp";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** Drive rejects uploads streamed without a known length on some proxies. */
    private static final int CHUNK_BYTES = 256 * 1024;

    private CloudHttp() {}

    static CloudResponse request(String method, String url, String bearer,
                            Map<String, String> headers, byte[] body,
                            String contentType) throws CloudException {
        HttpURLConnection connection = null;
        try {
            connection = open(method, url, bearer, headers);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                    out.flush();
                } finally {
                    close(out);
                }
            }
            return finish(connection);
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw network(e);
        } finally {
            disconnect(connection);
        }
    }

    /**
     * Streams {@code file} as the body. Used for content uploads, where
     * buffering a whole attachment into a byte array would be careless: vaults
     * legitimately hold images and PDFs.
     */
    static CloudResponse upload(String method, String url, String bearer,
                           Map<String, String> headers, File file,
                           String contentType) throws CloudException {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = open(method, url, bearer, headers);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(file.length());
            connection.setRequestProperty("Content-Type",
                    contentType == null ? "application/octet-stream" : contentType);
            in = new BufferedInputStream(new FileInputStream(file), CHUNK_BYTES);
            OutputStream out = connection.getOutputStream();
            try {
                byte[] buffer = new byte[CHUNK_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                close(out);
            }
            return finish(connection);
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw network(e);
        } finally {
            close(in);
            disconnect(connection);
        }
    }

    /**
     * A {@code multipart/related} create: JSON metadata in the first part, the
     * file's bytes streamed as the second.
     *
     * <p>Hand-rolled because the length has to be known up front - Drive's
     * upload endpoint behaves badly behind proxies with chunked transfer - and
     * because buffering an attachment into memory to let a library measure it
     * defeats the point.
     */
    static CloudResponse multipart(String url, String bearer, String metadataJson,
                              File file, String contentType) throws CloudException {
        final String boundary = "shard" + System.nanoTime();
        final String preamble = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadataJson + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: " + (contentType == null ? "application/octet-stream" : contentType)
                + "\r\n\r\n";
        final String epilogue = "\r\n--" + boundary + "--";

        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            byte[] head = preamble.getBytes("UTF-8");
            byte[] tail = epilogue.getBytes("UTF-8");
            connection = open("POST", url, bearer, null);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(head.length + file.length() + tail.length);
            in = new BufferedInputStream(new FileInputStream(file), CHUNK_BYTES);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(head);
                byte[] buffer = new byte[CHUNK_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.write(tail);
                out.flush();
            } finally {
                close(out);
            }
            return finish(connection);
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw network(e);
        } finally {
            close(in);
            disconnect(connection);
        }
    }

    private static HttpURLConnection open(String method, String url, String bearer,
                                          Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(patchable(connection, method));
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        connection.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        return connection;
    }

    /**
     * {@link HttpURLConnection} refuses PATCH outright. Drive accepts the
     * standard override header instead, which is what every Google client
     * library falls back to on Android.
     */
    private static String patchable(HttpURLConnection connection, String method) {
        if ("PATCH".equals(method)) {
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            return "POST";
        }
        return method;
    }

    private static CloudResponse finish(HttpURLConnection connection) throws CloudException, IOException {
        int status = connection.getResponseCode();
        String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status >= 400) {
            throw classify(status, body);
        }
        return new CloudResponse(status, body);
    }

    /**
     * Maps a failing status onto a reason. The response body is inspected only
     * to separate Drive's two very different meanings of 403 - "slow down" and
     * "you are out of storage" - and it is never logged, because Drive echoes
     * file names in error details.
     */
    private static CloudException classify(int status, String body) {
        switch (status) {
            case 401:
                return new CloudException(CloudException.Reason.AUTHENTICATION);
            case 404:
                return new CloudException(CloudException.Reason.NOT_FOUND);
            case 429:
                return new CloudException(CloudException.Reason.RATE_LIMIT);
            case 403: {
                String lower = body == null ? "" : body.toLowerCase();
                if (lower.contains("storagequotaexceeded")) {
                    return new CloudException(CloudException.Reason.QUOTA);
                }
                if (lower.contains("ratelimit") || lower.contains("userratelimitexceeded")
                        || lower.contains("quotaexceeded")) {
                    return new CloudException(CloudException.Reason.RATE_LIMIT);
                }
                return new CloudException(CloudException.Reason.AUTHENTICATION);
            }
            default:
                if (status >= 500) {
                    return new CloudException(CloudException.Reason.NETWORK);
                }
                Log.w(TAG, "unexpected status " + status);
                return new CloudException(CloudException.Reason.REMOTE);
        }
    }

    /**
     * Transport failures are all NETWORK: a timeout, a dropped TLS session and
     * a cancelled worker's interrupted write are indistinguishable here and,
     * more to the point, all worth retrying.
     */
    private static CloudException network(IOException e) {
        return new CloudException(CloudException.Reason.NETWORK, e);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            close(in);
        }
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }
}
