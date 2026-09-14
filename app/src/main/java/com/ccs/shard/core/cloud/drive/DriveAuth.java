package com.ccs.shard.core.cloud.drive;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;

import com.ccs.shard.core.cloud.CloudException;
import com.ccs.shard.core.cloud.CloudTokenSource;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

/**
 * Turns the account the user picked into a short-lived Drive access token.
 *
 * <p>Sign-in itself is a UI concern and lives in
 * {@code com.ccs.shard.ui.CloudSignIn}; this class only exchanges an account
 * name for a token, which is the part the background worker needs.
 *
 * <p>Tokens are held in memory only, never in preferences - the same rule
 * {@link com.ccs.shard.core.GitCredentials} follows. {@code GoogleAuthUtil}
 * keeps its own encrypted cache, so a cold start costs one cheap call rather
 * than a new consent prompt.
 */
public final class DriveAuth implements CloudTokenSource {

    private static final String TAG = "DriveAuth";

    /**
     * The narrow Drive scope: Shard sees only the files it created itself.
     *
     * <p>Deliberately not {@code drive} or {@code drive.readonly} - those are
     * restricted scopes, and shipping them on Play would require an annual
     * third-party security assessment. The cost is that Shard cannot adopt a
     * folder the user made by hand in the Drive web UI; it creates and owns
     * its own.
     */
    public static final String SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file";

    private final Context context;
    private final String accountName;
    private volatile String token;

    public DriveAuth(Context context, String accountName) {
        this.context = context.getApplicationContext();
        this.accountName = accountName;
    }

    public String accountName() {
        return accountName;
    }

    /** A usable bearer token, fetching one if needed. Blocking. */
    @Override public String token() throws CloudException {
        String cached = token;
        if (cached != null) return cached;
        return fetch();
    }

    /**
     * Discards the current token and fetches a fresh one. Called after a 401:
     * Google's cache can hand out a token that expired in flight, and the
     * documented remedy is to invalidate and retry exactly once.
     */
    @Override public String refresh() throws CloudException {
        String stale = token;
        token = null;
        if (stale != null) {
            try {
                GoogleAuthUtil.clearToken(context, stale);
            } catch (Throwable t) {
                Log.w(TAG, "cannot clear stale token", t);
            }
        }
        return fetch();
    }

    private String fetch() throws CloudException {
        if (accountName == null || accountName.isEmpty()) {
            throw new CloudException(CloudException.Reason.CONFIGURATION);
        }
        try {
            String fresh = GoogleAuthUtil.getToken(context, new Account(accountName, "com.google"), SCOPE);
            token = fresh;
            return fresh;
        } catch (UserRecoverableAuthException e) {
            // Consent was revoked or never granted for this scope. Only the
            // foreground sign-in flow can fix it.
            throw new CloudException(CloudException.Reason.AUTHENTICATION, e);
        } catch (GoogleAuthException e) {
            throw new CloudException(CloudException.Reason.AUTHENTICATION, e);
        } catch (java.io.IOException e) {
            throw new CloudException(CloudException.Reason.NETWORK, e);
        } catch (Throwable t) {
            throw new CloudException(CloudException.Reason.AUTHENTICATION, t);
        }
    }

    void forget() {
        token = null;
    }
}
