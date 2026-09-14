package com.ccs.shard.core.cloud;

/**
 * A source of bearer tokens, so the HTTP layer can retry a 401 without knowing
 * which provider - or which OAuth library - produced the token.
 */
public interface CloudTokenSource {

    /** A usable token, fetching one if needed. Blocking. */
    String token() throws CloudException;

    /** Invalidates the current token and returns a fresh one. Blocking. */
    String refresh() throws CloudException;
}
