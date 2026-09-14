package com.ccs.shard.ui;

import android.app.Activity;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.ccs.shard.R;
import com.ccs.shard.core.GitCredentials;
import com.ccs.shard.core.GitSyncConfig;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Programmatic Git forms shared by settings without leaking transport logic into the UI. */
public final class GitSyncDialogs {

    public interface ConfigListener { void onSave(GitSyncConfig config); }

    public interface CredentialsListener { void onContinue(GitCredentials credentials); }

    private GitSyncDialogs() { }

    public static void showConfig(Activity activity, GitSyncConfig current,
                                  ConfigListener listener) {
        LinearLayout fields = fields(activity);
        EditText remote = field(activity, R.string.git_remote_hint,
                current == null ? "" : current.getRemoteUrl());
        remote.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText branch = field(activity, R.string.git_branch_hint,
                current == null ? "main" : current.getBranch());
        EditText username = field(activity, R.string.git_username_hint,
                current == null ? "" : current.getUsername());
        EditText author = field(activity, R.string.git_author_hint,
                current == null ? "Shard" : current.getAuthorName());
        EditText email = field(activity, R.string.git_email_hint,
                current == null ? "shard@localhost" : current.getAuthorEmail());
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        fields.addView(remote);
        fields.addView(branch);
        fields.addView(username);
        fields.addView(author);
        fields.addView(email);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.git_configure)
                .setMessage(R.string.git_configure_hint)
                .setView(scroll(activity, fields))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> listener.onSave(
                        new GitSyncConfig(remote.getText().toString(),
                                branch.getText().toString(), username.getText().toString(),
                                author.getText().toString(), email.getText().toString())))
                .show();
    }

    public static void showCredentials(Activity activity, GitSyncConfig config,
                                       boolean push, CredentialsListener listener) {
        LinearLayout fields = fields(activity);
        EditText username = field(activity, R.string.git_username_hint, config.getUsername());
        EditText token = field(activity, R.string.git_token_hint, "");
        token.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(username);
        fields.addView(token);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(push ? R.string.git_push : R.string.git_pull)
                .setMessage(R.string.git_token_not_saved)
                .setView(fields)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.git_continue, (dialog, which) ->
                        listener.onContinue(new GitCredentials(username.getText().toString(),
                                token.getText().toString())))
                .show();
    }

    private static LinearLayout fields(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Ui.dp(activity, 20);
        layout.setPadding(horizontal, Ui.dp(activity, 4), horizontal, 0);
        return layout;
    }

    private static EditText field(Activity activity, int hint, String value) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(value);
        input.setSelectAllOnFocus(false);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static ScrollView scroll(Activity activity, LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }
}
