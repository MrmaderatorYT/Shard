package com.ccs.shard.base;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.ccs.shard.R;
import com.ccs.shard.ShardApp;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;

/**
 * Shared setup for every screen: theme, singletons, and the two or three UI
 * helpers that would otherwise be copy-pasted.
 *
 * <p>Theming is applied the standard way — {@link AppCompatDelegate} for
 * light/dark and a style overlay for the accent — rather than by rewriting the
 * {@link android.content.res.Configuration} in {@code attachBaseContext}, which
 * the previous version did and which broke resource resolution for anything
 * loaded through a non-activity context.
 */
public abstract class BaseActivity extends AppCompatActivity {

    protected Prefs prefs;
    protected VaultRepository repo;

    private int appliedTheme;
    private int appliedAccent;
    private boolean appliedDynamic;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = ShardApp.get().prefs();
        repo = ShardApp.get().repository();

        appliedTheme = prefs.themeMode();
        appliedAccent = prefs.accent();
        appliedDynamic = prefs.dynamicColor();
        applyAppTheme();

        super.onCreate(savedInstanceState);
    }

    private void applyAppTheme() {
        int mode = prefs.themeMode();
        switch (mode) {
            case Prefs.THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case Prefs.THEME_DARK:
            case Prefs.THEME_BLACK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
        setTheme(mode == Prefs.THEME_BLACK ? R.style.Theme_Shard_Black : R.style.Theme_Shard);
        getTheme().applyStyle(accentStyle(prefs.accent()), true);

        if (prefs.dynamicColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
    }

    private static int accentStyle(int accent) {
        if (accent == Prefs.ACCENTS[1]) return R.style.Accent_Violet;
        if (accent == Prefs.ACCENTS[2]) return R.style.Accent_Green;
        if (accent == Prefs.ACCENTS[3]) return R.style.Accent_Amber;
        if (accent == Prefs.ACCENTS[4]) return R.style.Accent_Pink;
        if (accent == Prefs.ACCENTS[5]) return R.style.Accent_Teal;
        if (accent == Prefs.ACCENTS[6]) return R.style.Accent_Graphite;
        return R.style.Accent_Blue;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedTheme != prefs.themeMode()
                || appliedAccent != prefs.accent()
                || appliedDynamic != prefs.dynamicColor()) {
            recreate();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Public alias of {@link #dialog()} for collaborator classes in other packages. */
    public MaterialAlertDialogBuilder dialogBuilder() {
        return dialog();
    }

    /** Public alias of {@link #confirm} for collaborator classes in other packages. */
    public void confirmAction(@StringRes int titleRes, CharSequence message,
                              @StringRes int confirmRes, Runnable onConfirm) {
        confirm(titleRes, message, confirmRes, onConfirm);
    }

    protected MaterialAlertDialogBuilder dialog() {
        return new MaterialAlertDialogBuilder(this);
    }

    /** Confirmation dialog with a destructive primary action. */
    protected void confirm(@StringRes int titleRes, CharSequence message,
                           @StringRes int confirmRes, final Runnable onConfirm) {
        dialog()
                .setTitle(titleRes)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(confirmRes, (d, which) -> onConfirm.run())
                .show();
    }

    public void toast(@StringRes int messageRes) {
        toast(getString(messageRes));
    }

    public void toast(CharSequence message) {
        View root = findViewById(android.R.id.content);
        if (root != null) {
            Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(this, message,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Snackbar with a single action, used for undo affordances. */
    protected void toastWithAction(CharSequence message, @StringRes int actionRes,
                                   final Runnable action) {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            toast(message);
            return;
        }
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
                .setAction(actionRes, v -> action.run())
                .show();
    }

    protected void copyToClipboard(CharSequence text, @StringRes int confirmationRes) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Shard", text));
        // Android 13+ shows its own copy confirmation; a second one is noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(confirmationRes);
    }

    /** Shares an exported file through the system chooser. */
    public void shareFile(File file, String mimeType, CharSequence subject) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Throwable t) {
            toast(R.string.export_failed);
        }
    }

    /** Opens an exported file directly in a viewer app (e.g. PDF reader). */
    public void viewFile(File file, String mimeType) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            shareFile(file, mimeType, file.getName());
        }
    }
}
