package com.ccs.shard.ui;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;

import com.ccs.shard.core.cloud.drive.DriveAuth;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

/**
 * The foreground half of Google sign-in: proving that a chosen account will
 * actually hand out Drive tokens.
 *
 * <p>Separate from {@link DriveAuth} because of one asymmetry.
 * {@code UserRecoverableAuthException} carries an {@link Intent} that shows
 * the consent screen, and only an Activity can launch it - so the background
 * sync path flattens that case into an authentication error, while this class
 * preserves the intent so settings can ask for consent there and then.
 */
public final class CloudSignIn {

    /** The three ways a token request can end. */
    public static final class Probe {
        /** The account works; sync can be enabled. */
        public final boolean granted;
        /** Non-null when the user has to approve access first. */
        public final Intent consent;
        /** True when the failure is a network problem rather than a refusal. */
        public final boolean network;

        private Probe(boolean granted, Intent consent, boolean network) {
            this.granted = granted;
            this.consent = consent;
            this.network = network;
        }
    }

    private CloudSignIn() {}

    /**
     * Asks for a Drive token for {@code accountName}.
     *
     * <p>Blocking; call through {@link com.ccs.shard.core.Io#load}. The token
     * itself is discarded - this only establishes that consent exists, and the
     * sync path fetches its own from Google's cache moments later.
     */
    public static Probe probe(Context context, String accountName) {
        if (accountName == null || accountName.isEmpty()) {
            return new Probe(false, null, false);
        }
        try {
            GoogleAuthUtil.getToken(context.getApplicationContext(),
                    new Account(accountName, "com.google"), DriveAuth.SCOPE);
            return new Probe(true, null, false);
        } catch (UserRecoverableAuthException e) {
            return new Probe(false, e.getIntent(), false);
        } catch (java.io.IOException e) {
            return new Probe(false, null, true);
        } catch (Throwable t) {
            // Also covers the misconfiguration case: an OAuth client whose
            // package name and signing certificate do not match this build
            // fails here rather than at the account picker.
            return new Probe(false, null, false);
        }
    }

    /** Intent for the system Google-account chooser. Needs no permission. */
    public static Intent accountPicker() {
        return android.accounts.AccountManager.newChooseAccountIntent(
                null, null, new String[]{"com.google"}, null, null, null, null);
    }
}
