package com.ccs.shard;

import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.cloud.CloudException;
import com.ccs.shard.core.cloud.CloudProvider;
import com.ccs.shard.core.cloud.CloudSyncManager;
import com.ccs.shard.ui.CloudSignIn;
import com.ccs.shard.core.AutoBackupManager;
import com.ccs.shard.core.CrashReporter;
import com.ccs.shard.core.FolderSync;
import com.ccs.shard.core.GitCredentials;
import com.ccs.shard.core.GitSyncConfig;
import com.ccs.shard.core.GitSyncException;
import com.ccs.shard.core.GitSyncResult;
import com.ccs.shard.core.GitSyncService;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.GitSyncDialogs;
import com.ccs.shard.ui.Ui;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.util.List;

/**
 * Settings.
 *
 * <p>Rows are built in code from the {@link Prefs} that actually exist, which
 * keeps the screen from drifting out of sync with the app — the previous version
 * offered switches for behaviour that had been removed. Grouped by what the user
 * is trying to change rather than by which class stores it.
 */
public final class SettingsActivity extends BaseActivity {

    private LinearLayout container;
    private ActivityResultLauncher<Uri> syncFolderPicker;
    private ActivityResultLauncher<Intent> cloudAccountPicker;
    private ActivityResultLauncher<Intent> cloudConsent;
    /** Account being verified across the picker/consent round trip. */
    private String pendingCloudAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        syncFolderPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onSyncFolderPicked);
        cloudAccountPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onCloudAccountPicked);
        cloudConsent = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onCloudConsent);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        container = findViewById(R.id.settingsContainer);
        build();
    }

    private void build() {
        container.removeAllViews();

        section(R.string.settings_appearance);
        valueRow(R.drawable.ic_palette, getString(R.string.settings_theme),
                themeName(prefs.themeMode()), this::pickTheme);
        accentRow();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            switchRow(getString(R.string.settings_dynamic),
                    getString(R.string.settings_dynamic_hint),
                    prefs.dynamicColor(), checked -> {
                        prefs.setDynamicColor(checked);
                        recreate();
                    });
        }

        section(R.string.settings_editor);
        sliderRow(getString(R.string.settings_font_size), prefs.fontSize(), 12, 26, 1,
                value -> prefs.setFontSize((int) value));
        valueRow(R.drawable.ic_font_size, getString(R.string.settings_font_family),
                fontName(prefs.fontFamily()), this::pickFont);
        switchRow(getString(R.string.settings_wide_editor),
                getString(R.string.settings_wide_editor_hint),
                prefs.wideEditor(), prefs::setWideEditor);
        sliderRow(getString(R.string.settings_autosave), prefs.autosaveDelayMs(),
                300, 3000, 100, value -> prefs.setAutosaveDelayMs((int) value),
                value -> getString(R.string.settings_autosave_hint, (int) value));

        section(R.string.settings_behaviour);
        switchRow(getString(R.string.settings_confirm_delete), null,
                prefs.confirmDelete(), prefs::setConfirmDelete);
        switchRow(getString(R.string.settings_haptics), null,
                prefs.haptics(), prefs::setHaptics);
        sliderRow(getString(R.string.settings_trash_retention), prefs.trashRetentionDays(),
                1, 90, 1, value -> prefs.setTrashRetentionDays((int) value),
                value -> getString(R.string.settings_trash_days, (int) value));

        section(R.string.settings_storage);
        infoRow(R.drawable.ic_folder, getString(R.string.settings_vault_path),
                repo.vault().displayPath());
        actionRow(R.drawable.ic_undo, getString(R.string.settings_reindex),
                getString(R.string.settings_reindex_hint), this::reindex);
        addVaultLocationRow();
        addFolderSyncRows();
        addGitSyncRows();
        addCloudSyncRows();
        addAutomaticBackupRow();

        section(R.string.settings_about);
        infoRow(R.drawable.ic_info, getString(R.string.app_name),
                getString(R.string.settings_version, BuildConfig.VERSION_NAME));
        infoRow(R.drawable.ic_notes, getString(R.string.nav_vault),
                getString(R.string.settings_notes_stat,
                        repo.index().size(),
                        repo.index().allTags().size(),
                        countLinks()));
        addCrashReportRow();
        actionRow(R.drawable.ic_clear, getString(R.string.settings_reset), null,
                () -> confirm(R.string.settings_reset_q,
                        getString(R.string.settings_reset_body),
                        R.string.settings_reset, () -> {
                            prefs.resetToDefaults();
                            recreate();
                        }));
    }

    private void addAutomaticBackupRow() {
        List<File> backups = new AutoBackupManager(this, repo).backups();
        String subtitle = backups.isEmpty() ? getString(R.string.automatic_backup_none)
                : getResources().getQuantityString(R.plurals.automatic_backup_count,
                        backups.size(), backups.size());
        valueRow(R.drawable.ic_backup, getString(R.string.automatic_backups), subtitle,
                this::showAutomaticBackups);
    }

    private void showAutomaticBackups(View anchor) {
        List<File> backups = new AutoBackupManager(this, repo).backups();
        AnchoredMenu menu = AnchoredMenu.vertical(this).title(getString(R.string.automatic_backups));
        menu.add(1, R.drawable.ic_backup, getString(R.string.backup_create_now));
        if (!backups.isEmpty()) {
            menu.add(2, R.drawable.ic_share, getString(R.string.backup_share_latest));
            menu.add(new AnchoredMenu.Item(3, R.drawable.ic_delete,
                    getString(R.string.backup_clear_all)).destructive());
        }
        menu.onItem(id -> {
            if (id == 1) {
                toast(R.string.backup_creating);
                Io.load(() -> new AutoBackupManager(this, repo).backupIfDue(true),
                        new Io.Result<File>() {
                            @Override public void onReady(File file) {
                                toast(file == null ? R.string.export_failed : R.string.backup_created);
                                build();
                            }
                            @Override public void onError(Throwable error) { toast(R.string.export_failed); }
                        });
            } else if (id == 2 && !backups.isEmpty()) {
                shareFile(backups.get(0), "application/zip", backups.get(0).getName());
            } else if (id == 3) {
                confirm(R.string.backup_clear_all, getString(R.string.backup_clear_confirm),
                        R.string.backup_clear_all, () -> {
                            for (File file : new AutoBackupManager(this, repo).backups()) {
                                //noinspection ResultOfMethodCallIgnored
                                file.delete();
                            }
                            build();
                        });
            }
        }).showAt(anchor);
    }

    private void addCrashReportRow() {
        List<File> reports = CrashReporter.reports(this);
        if (reports.isEmpty()) return;
        valueRow(R.drawable.ic_info, getString(R.string.crash_reports),
                getResources().getQuantityString(R.plurals.crash_report_count,
                        reports.size(), reports.size()), this::showCrashReports);
    }

    private void showCrashReports(View anchor) {
        File latest = CrashReporter.latest(this);
        AnchoredMenu.vertical(this).title(getString(R.string.crash_reports))
                .add(1, R.drawable.ic_share, getString(R.string.crash_share_latest))
                .add(new AnchoredMenu.Item(2, R.drawable.ic_delete,
                        getString(R.string.crash_clear)).destructive())
                .onItem(id -> {
                    if (id == 1 && latest != null) {
                        shareFile(latest, "text/plain", latest.getName());
                        prefs.setPendingCrashReport(false);
                    } else if (id == 2) {
                        confirm(R.string.crash_clear, getString(R.string.crash_clear_confirm),
                                R.string.crash_clear, () -> {
                                    CrashReporter.clear(this);
                                    build();
                                });
                    }
                }).showAt(anchor);
    }

    private void addFolderSyncRows() {
        String saved = prefs.syncTreeUri();
        if (saved == null) {
            actionRow(R.drawable.ic_backup, getString(R.string.settings_sync_choose),
                    getString(R.string.settings_sync_choose_hint),
                    () -> syncFolderPicker.launch(null));
            return;
        }
        String name = saved;
        try {
            androidx.documentfile.provider.DocumentFile folder =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(saved));
            if (folder != null && folder.getName() != null) name = folder.getName();
        } catch (Throwable ignored) {}
        infoRow(R.drawable.ic_folder, getString(R.string.settings_sync_folder), name);
        String last = prefs.lastSyncMillis() <= 0 ? getString(R.string.settings_sync_never)
                : com.ccs.shard.util.RelativeTime.format(this, prefs.lastSyncMillis()).toString();
        actionRow(R.drawable.ic_backup, getString(R.string.settings_sync_now), last, this::syncNow);
        actionRow(R.drawable.ic_close, getString(R.string.settings_sync_disconnect),
                getString(R.string.settings_sync_disconnect_hint), () -> {
                    prefs.setSyncTreeUri(null);
                    VaultSyncWorker.cancel(this);
                    build();
                });
    }

    private void onSyncFolderPicked(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {}
        prefs.setSyncTreeUri(uri.toString());
        VaultSyncWorker.schedule(this);
        requestSyncNotificationPermission();
        build();
        syncNow();
    }

    /** Conflict alerts are useful only when the user explicitly enables folder sync. */
    private void requestSyncNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
                || androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        androidx.core.app.ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 43);
    }

    private void syncNow() {
        String saved = prefs.syncTreeUri();
        if (saved == null) return;
        toast(R.string.settings_sync_running);
        Io.load(() -> {
                    // A full ZIP immediately before two-way writes makes even a
                    // provider bug or malformed conflict state recoverable.
                    new AutoBackupManager(this, repo).backupIfDue(true);
                    return new FolderSync(this, repo).sync(Uri.parse(saved));
                },
                new Io.Result<FolderSync.Result>() {
                    @Override public void onReady(FolderSync.Result result) {
                        prefs.setLastSyncMillis(System.currentTimeMillis());
                        toast(getString(R.string.settings_sync_done,
                                result.pulled, result.pushed, result.conflicts));
                        build();
                    }

                    @Override public void onError(Throwable error) {
                        toast(R.string.settings_sync_failed);
                    }
                });
    }

    private void addCloudSyncRows() {
        CloudSyncManager cloud = CloudSyncManager.get(this);
        CloudProvider provider = prefs.cloudProvider();
        if (provider == CloudProvider.NONE) {
            actionRow(R.drawable.ic_backup, getString(R.string.cloud_connect),
                    getString(R.string.cloud_connect_hint), this::chooseCloudProvider);
            return;
        }

        String account = prefs.cloudAccount();
        infoRow(R.drawable.ic_backup, getString(cloudProviderLabel(provider)),
                account == null ? getString(R.string.cloud_no_account) : account);
        infoRow(R.drawable.ic_folder, getString(R.string.cloud_folder), prefs.cloudFolderName());

        String error = prefs.cloudLastError();
        int pending = cloud.pendingCount();
        String status;
        if (error != null) {
            status = getString(cloudErrorMessage(error));
        } else if (pending > 0) {
            status = getString(R.string.cloud_pending, pending);
        } else if (prefs.cloudLastSyncMillis() > 0) {
            status = com.ccs.shard.util.RelativeTime.format(
                    this, prefs.cloudLastSyncMillis()).toString();
        } else {
            status = getString(R.string.settings_sync_never);
        }
        actionRow(R.drawable.ic_arrow_up, getString(R.string.cloud_sync_now), status,
                this::cloudSyncNow);
        actionRow(R.drawable.ic_settings, getString(R.string.cloud_rename_folder),
                getString(R.string.cloud_rename_folder_hint), this::renameCloudFolder);
        actionRow(R.drawable.ic_close, getString(R.string.cloud_disconnect),
                getString(R.string.cloud_disconnect_hint), this::disconnectCloud);
    }

    private void chooseCloudProvider() {
        // Only one provider is implemented, so skip a one-item menu; the enum
        // already carries the rest for when Dropbox lands.
        startCloudSignIn();
    }

    private void startCloudSignIn() {
        try {
            cloudAccountPicker.launch(CloudSignIn.accountPicker());
        } catch (Throwable t) {
            toast(R.string.cloud_no_accounts);
        }
    }

    private void onCloudAccountPicked(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
        String account = result.getData().getStringExtra(
                android.accounts.AccountManager.KEY_ACCOUNT_NAME);
        if (account == null || account.isEmpty()) {
            toast(R.string.cloud_no_accounts);
            return;
        }
        pendingCloudAccount = account;
        verifyCloudAccount(account);
    }

    private void onCloudConsent(androidx.activity.result.ActivityResult result) {
        String account = pendingCloudAccount;
        if (account == null) return;
        if (result.getResultCode() != RESULT_OK) {
            toast(R.string.cloud_consent_declined);
            pendingCloudAccount = null;
            return;
        }
        verifyCloudAccount(account);
    }

    private void verifyCloudAccount(final String account) {
        toast(R.string.cloud_connecting);
        Io.load(() -> CloudSignIn.probe(this, account),
                new Io.Result<CloudSignIn.Probe>() {
                    @Override public void onReady(CloudSignIn.Probe probe) {
                        if (probe.granted) {
                            pendingCloudAccount = null;
                            CloudSyncManager.get(SettingsActivity.this)
                                    .connect(CloudProvider.GOOGLE_DRIVE, account);
                            toast(R.string.cloud_connected);
                            build();
                            return;
                        }
                        if (probe.consent != null) {
                            // First run for this scope, or consent was revoked.
                            cloudConsent.launch(probe.consent);
                            return;
                        }
                        pendingCloudAccount = null;
                        toast(probe.network ? R.string.cloud_network_failed
                                : R.string.cloud_auth_failed);
                    }

                    @Override public void onError(Throwable error) {
                        pendingCloudAccount = null;
                        toast(R.string.cloud_auth_failed);
                    }
                });
    }

    private void cloudSyncNow() {
        toast(R.string.cloud_syncing);
        CloudSyncManager.get(this).syncNow();
    }

    private void renameCloudFolder() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.cloudFolderName());
        input.setSelection(input.getText().length());
        dialog().setTitle(R.string.cloud_rename_folder)
                .setMessage(R.string.cloud_rename_folder_dialog)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (name.equals(prefs.cloudFolderName())) return;
                    prefs.setCloudFolderName(name);
                    // The old remote folder keeps the files already uploaded;
                    // forgetting their ids is what makes the next sync refill
                    // the new folder instead of updating files in the old one.
                    CloudSyncManager.get(this).index().clear();
                    CloudSyncManager.get(this).reconcileAndSync();
                    build();
                })
                .show();
    }

    private void disconnectCloud() {
        confirm(R.string.cloud_disconnect, getString(R.string.cloud_disconnect_confirm),
                R.string.cloud_disconnect, () -> {
            CloudSyncManager.get(this).disconnect();
            build();
        });
    }

    private static int cloudProviderLabel(CloudProvider provider) {
        switch (provider) {
            case GOOGLE_DRIVE: return R.string.cloud_google_drive;
            case DROPBOX: return R.string.cloud_dropbox;
            default: return R.string.cloud_connect;
        }
    }

    private static int cloudErrorMessage(String reason) {
        try {
            switch (CloudException.Reason.valueOf(reason)) {
                case AUTHENTICATION: return R.string.cloud_auth_failed;
                case NETWORK: return R.string.cloud_network_failed;
                case QUOTA: return R.string.cloud_quota_failed;
                case RATE_LIMIT: return R.string.cloud_rate_limited;
                case CONFIGURATION: return R.string.cloud_not_configured;
                default: return R.string.cloud_failed;
            }
        } catch (Throwable t) {
            return R.string.cloud_failed;
        }
    }

    private void addGitSyncRows() {
        GitSyncConfig config = prefs.gitSyncConfig();
        if (config == null) {
            actionRow(R.drawable.ic_git_branch, getString(R.string.git_configure),
                    getString(R.string.git_configure_hint), this::configureGit);
            return;
        }
        String remote = config.getRemoteUrl() + " · " + config.getBranch();
        infoRow(R.drawable.ic_git_branch, getString(R.string.git_repository), remote);
        String last = prefs.lastGitSyncMillis() <= 0 ? getString(R.string.settings_sync_never)
                : com.ccs.shard.util.RelativeTime.format(
                        this, prefs.lastGitSyncMillis()).toString();
        actionRow(R.drawable.ic_arrow_down, getString(R.string.git_pull), last,
                () -> requestGitCredentials(false));
        actionRow(R.drawable.ic_arrow_up, getString(R.string.git_push),
                getString(R.string.git_push_hint), () -> requestGitCredentials(true));
        actionRow(R.drawable.ic_settings, getString(R.string.git_edit),
                getString(R.string.git_edit_hint), this::configureGit);
        actionRow(R.drawable.ic_close, getString(R.string.git_disconnect),
                getString(R.string.git_disconnect_hint), this::disconnectGit);
    }

    private void configureGit() {
        GitSyncConfig previous = prefs.gitSyncConfig();
        GitSyncDialogs.showConfig(this, previous, config -> {
            if (!config.isValid()) {
                toast(R.string.git_invalid_config);
                return;
            }
            boolean endpointChanged = previous == null
                    || !previous.getRemoteUrl().equals(config.getRemoteUrl())
                    || !previous.getBranch().equals(config.getBranch());
            prefs.setGitSyncConfig(config);
            if (endpointChanged) Io.onDisk(() -> new GitSyncService(this, repo).clearWorkspace());
            build();
        });
    }

    private void requestGitCredentials(boolean push) {
        GitSyncConfig config = prefs.gitSyncConfig();
        if (config == null) return;
        GitSyncDialogs.showCredentials(this, config, push,
                credentials -> runGitSync(config, credentials, push));
    }

    private void runGitSync(GitSyncConfig config, GitCredentials credentials, boolean push) {
        toast(push ? R.string.git_pushing : R.string.git_pulling);
        Io.load(() -> new GitSyncService(this, repo).sync(config, credentials, push),
                new Io.Result<GitSyncResult>() {
                    @Override public void onReady(GitSyncResult result) {
                        prefs.setLastGitSyncMillis(System.currentTimeMillis());
                        toast(getString(push ? R.string.git_push_done : R.string.git_pull_done,
                                result.pulled, result.pushed, result.conflicts));
                        build();
                    }

                    @Override public void onError(Throwable error) {
                        toast(gitErrorMessage(error));
                    }
                });
    }

    private int gitErrorMessage(Throwable error) {
        if (!(error instanceof GitSyncException)) return R.string.git_failed;
        switch (((GitSyncException) error).getReason()) {
            case AUTHENTICATION: return R.string.git_auth_failed;
            case NETWORK: return R.string.git_network_failed;
            case BRANCH: return R.string.git_branch_failed;
            case CONFLICT: return R.string.git_conflict_failed;
            default: return R.string.git_failed;
        }
    }

    private void disconnectGit() {
        prefs.clearGitSyncConfig();
        Io.onDisk(() -> new GitSyncService(this, repo).clearWorkspace());
        build();
    }

    /** Documents/Shard is the canonical vault; this row only resolves missing access. */
    private void addVaultLocationRow() {
        // The vault is app-private. Shared files are accessed through Import,
        // Export, and the SAF-backed sync folder below.
    }

    private void restartApp() {
        android.content.Intent intent = new android.content.Intent(this, HomeActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
        // Process restart is required: VaultRepository is a process-wide singleton
        // holding the old root.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> System.exit(0), 250);
    }

    private int countLinks() {
        int total = 0;
        for (com.ccs.shard.core.Note note : repo.index().all()) {
            total += note.getOutgoingLinks().size();
        }
        return total;
    }

    private void reindex() {
        repo.refresh();
        Io.onMainDelayed(() -> {
            toast(getString(R.string.settings_reindexed, repo.index().size()));
            build();
        }, 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (container != null) build();
    }

    // ---------------------------------------------------------------- pickers

    private void pickTheme(View anchor) {
        AnchoredMenu.vertical(this)
                .title(getString(R.string.settings_theme))
                .add(new AnchoredMenu.Item(Prefs.THEME_SYSTEM, 0,
                        getString(R.string.settings_theme_system))
                        .checked(prefs.themeMode() == Prefs.THEME_SYSTEM))
                .add(new AnchoredMenu.Item(Prefs.THEME_LIGHT, 0,
                        getString(R.string.settings_theme_light))
                        .checked(prefs.themeMode() == Prefs.THEME_LIGHT))
                .add(new AnchoredMenu.Item(Prefs.THEME_DARK, 0,
                        getString(R.string.settings_theme_dark))
                        .checked(prefs.themeMode() == Prefs.THEME_DARK))
                .add(new AnchoredMenu.Item(Prefs.THEME_BLACK, 0,
                        getString(R.string.settings_theme_black))
                        .checked(prefs.themeMode() == Prefs.THEME_BLACK))
                .onItem(id -> {
                    prefs.setThemeMode(id);
                    recreate();
                })
                .showAt(anchor);
    }

    private void pickFont(View anchor) {
        AnchoredMenu.vertical(this)
                .title(getString(R.string.settings_font_family))
                .add(new AnchoredMenu.Item(Prefs.FONT_SANS, 0,
                        getString(R.string.settings_font_sans))
                        .checked(prefs.fontFamily() == Prefs.FONT_SANS))
                .add(new AnchoredMenu.Item(Prefs.FONT_SERIF, 0,
                        getString(R.string.settings_font_serif))
                        .checked(prefs.fontFamily() == Prefs.FONT_SERIF))
                .add(new AnchoredMenu.Item(Prefs.FONT_MONO, 0,
                        getString(R.string.settings_font_mono))
                        .checked(prefs.fontFamily() == Prefs.FONT_MONO))
                .onItem(id -> {
                    prefs.setFontFamily(id);
                    build();
                })
                .showAt(anchor);
    }

    private String themeName(int mode) {
        switch (mode) {
            case Prefs.THEME_LIGHT: return getString(R.string.settings_theme_light);
            case Prefs.THEME_DARK: return getString(R.string.settings_theme_dark);
            case Prefs.THEME_BLACK: return getString(R.string.settings_theme_black);
            default: return getString(R.string.settings_theme_system);
        }
    }

    private String fontName(int family) {
        switch (family) {
            case Prefs.FONT_SERIF: return getString(R.string.settings_font_serif);
            case Prefs.FONT_MONO: return getString(R.string.settings_font_mono);
            default: return getString(R.string.settings_font_sans);
        }
    }

    // ---------------------------------------------------------------- row builders

    private interface OnToggle {
        void onChanged(boolean checked);
    }

    private interface OnValue {
        void onChanged(float value);
    }

    private interface Formatter {
        String format(float value);
    }

    private interface OnAnchorClick {
        void onClick(View anchor);
    }

    private void section(int labelRes) {
        TextView view = new TextView(this);
        view.setText(labelRes);
        view.setTextSize(11f);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        view.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary));
        Ui.setPaddingDp(view, 20, 20, 20, 6);
        container.addView(view);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        Ui.setPaddingDp(row, 20, 8, 20, 8);
        container.addView(row);
        return row;
    }

    private LinearLayout textColumn(String title, String subtitle) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        column.addView(titleView);

        if (subtitle != null) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(12f);
            subtitleView.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            column.addView(subtitleView);
        }
        return column;
    }

    private void addIcon(LinearLayout row, int iconRes) {
        if (iconRes == 0) return;
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        lp.rightMargin = Ui.dp(this, 16);
        icon.setLayoutParams(lp);
        row.addView(icon);
    }

    private void switchRow(String title, String subtitle, boolean checked,
                           final OnToggle listener) {
        LinearLayout row = baseRow();
        row.addView(textColumn(title, subtitle));
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((view, isChecked) -> listener.onChanged(isChecked));
        row.addView(toggle);
        row.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.08f), null));
        row.setOnClickListener(v -> toggle.toggle());
    }

    private void valueRow(int iconRes, String title, String value,
                          final OnAnchorClick listener) {
        LinearLayout row = baseRow();
        addIcon(row, iconRes);
        row.addView(textColumn(title, value));
        row.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.08f), null));
        row.setOnClickListener(listener::onClick);
    }

    private void infoRow(int iconRes, String title, String value) {
        LinearLayout row = baseRow();
        addIcon(row, iconRes);
        row.addView(textColumn(title, value));
    }

    private void actionRow(int iconRes, String title, String subtitle, final Runnable action) {
        LinearLayout row = baseRow();
        addIcon(row, iconRes);
        row.addView(textColumn(title, subtitle));
        row.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.08f), null));
        row.setOnClickListener(v -> action.run());
    }

    private void sliderRow(String title, int value, int from, int to, int step,
                           OnValue listener) {
        sliderRow(title, value, from, to, step, listener,
                v -> String.valueOf((int) v));
    }

    private void sliderRow(String title, int value, int from, int to, int step,
                           final OnValue listener, final Formatter formatter) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(column, 20, 10, 20, 4);

        final TextView label = new TextView(this);
        label.setTextSize(15f);
        label.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        label.setText(title);
        column.addView(label);

        final TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setText(formatter.format(value));
        column.addView(hint);

        Slider slider = new Slider(this);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setValue(Math.max(from, Math.min(to, value)));
        slider.addOnChangeListener((view, newValue, fromUser) -> {
            hint.setText(formatter.format(newValue));
            if (fromUser) listener.onChanged(newValue);
        });
        column.addView(slider);
        container.addView(column);
    }

    private void accentRow() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(column, 20, 8, 20, 8);

        TextView label = new TextView(this);
        label.setText(R.string.settings_accent);
        label.setTextSize(15f);
        label.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        column.addView(label);

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, Ui.dp(this, 10), 0, 0);
        for (final int accent : Prefs.ACCENTS) {
            View swatch = new View(this);
            boolean selected = prefs.accent() == accent;
            swatch.setBackground(Ui.roundRect(accent, Ui.dp(this, 16),
                    selected ? Ui.themeColor(this,
                            com.google.android.material.R.attr.colorOnSurface) : accent,
                    Ui.dp(this, selected ? 2 : 0)));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
            lp.rightMargin = Ui.dp(this, 10);
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                prefs.setAccent(accent);
                recreate();
            });
            swatches.addView(swatch);
        }
        column.addView(swatches);
        container.addView(column);
    }
}
