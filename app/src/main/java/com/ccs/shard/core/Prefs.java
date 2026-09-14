package com.ccs.shard.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * User settings.
 *
 * <p>Deliberately small. The previous version exposed two dozen knobs — separate
 * editor background and text colours, line numbers, navigation-bar visibility —
 * which made the settings screen the least understandable part of the app while
 * most of them had no visible effect. What remains is the set that changes how
 * the app actually feels to use.
 */
public final class Prefs implements NoteNavigationHistory {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;
    public static final int THEME_BLACK = 3;

    public static final int SORT_MODIFIED = 0;
    public static final int SORT_CREATED = 1;
    public static final int SORT_TITLE = 2;
    public static final int SORT_SIZE = 3;

    public static final int FONT_SANS = 0;
    public static final int FONT_SERIF = 1;
    public static final int FONT_MONO = 2;

    /** Accent choices offered in settings, in presentation order. */
    public static final int[] ACCENTS = {
            0xFF2F6FEB, // blue
            0xFF6E56CF, // violet
            0xFF0F9D58, // green
            0xFFD9480F, // amber
            0xFFC2255C, // pink
            0xFF0B7285, // teal
            0xFF495057, // graphite
    };

    private static final String NAME = "shard_prefs";

    private static final String KEY_THEME = "theme";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_DYNAMIC = "dynamic_color";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_WIDE_EDITOR = "wide_editor";
    private static final String KEY_AUTOSAVE_MS = "autosave_ms";
    private static final String KEY_SORT = "sort";
    private static final String KEY_SORT_DESC = "sort_desc";
    private static final String KEY_SHOW_ARCHIVED = "show_archived";
    private static final String KEY_GROUP_FOLDERS = "group_folders";
    private static final String KEY_LAST_FOLDER = "last_folder";
    private static final String KEY_CONFIRM_DELETE = "confirm_delete";
    private static final String KEY_HAPTICS = "haptics";
    private static final String KEY_RECENTS = "recents";
    private static final String KEY_LAST_NOTE = "last_note";
    private static final String KEY_GRAPH_TAGS = "graph_tags";
    private static final String KEY_GRAPH_LABELS = "graph_labels";
    private static final String KEY_GRAPH_ORPHANS = "graph_orphans";
    private static final String KEY_TEX_SPLIT_RATIO = "tex_split_ratio";
    private static final String KEY_TEX_PREVIEW_OPEN = "tex_preview_open";
    private static final String KEY_NOTE_SPLIT_RATIO = "note_split_ratio";
    private static final String KEY_NOTE_PREVIEW_OPEN = "note_preview_open";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_TRASH_DAYS = "trash_days";
    private static final String KEY_SYNC_TREE = "sync_tree_uri";
    private static final String KEY_SYNC_LAST = "sync_last";
    private static final String KEY_GIT_REMOTE = "git_remote";
    private static final String KEY_GIT_BRANCH = "git_branch";
    private static final String KEY_GIT_USERNAME = "git_username";
    private static final String KEY_GIT_AUTHOR = "git_author";
    private static final String KEY_GIT_EMAIL = "git_email";
    private static final String KEY_GIT_LAST = "git_last";
    private static final String KEY_AUTO_BACKUP_LAST = "auto_backup_last";
    private static final String KEY_LAST_IMPORT_URI = "last_import_uri";
    private static final String KEY_LAST_EXPORT_URI = "last_export_uri";
    private static final String KEY_NAV_HISTORY = "note_navigation_history";
    private static final String KEY_NAV_INDEX = "note_navigation_index";
    private static final String KEY_PENDING_CRASH = "pending_crash_report";
    private static final String KEY_CLOUD_PROVIDER = "cloud_provider";
    private static final String KEY_CLOUD_ACCOUNT = "cloud_account";
    private static final String KEY_CLOUD_FOLDER = "cloud_folder";
    private static final String KEY_CLOUD_LAST = "cloud_last";
    private static final String KEY_CLOUD_ERROR = "cloud_error";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- appearance

    public int themeMode() { return prefs.getInt(KEY_THEME, THEME_SYSTEM); }

    public void setThemeMode(int mode) { prefs.edit().putInt(KEY_THEME, mode).apply(); }

    public int accent() { return prefs.getInt(KEY_ACCENT, ACCENTS[0]); }

    public void setAccent(int color) { prefs.edit().putInt(KEY_ACCENT, color).apply(); }

    /** Material You colours, honoured only on API 31+. */
    public boolean dynamicColor() { return prefs.getBoolean(KEY_DYNAMIC, false); }

    public void setDynamicColor(boolean enabled) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply();
    }

    /** Editor body text size in sp. */
    public int fontSize() { return prefs.getInt(KEY_FONT_SIZE, 16); }

    public void setFontSize(int sp) {
        prefs.edit().putInt(KEY_FONT_SIZE, Math.max(12, Math.min(26, sp))).apply();
    }

    public int fontFamily() { return prefs.getInt(KEY_FONT_FAMILY, FONT_SANS); }

    public void setFontFamily(int family) { prefs.edit().putInt(KEY_FONT_FAMILY, family).apply(); }

    /** Full-width editor instead of a centred, book-width column on tablets. */
    public boolean wideEditor() { return prefs.getBoolean(KEY_WIDE_EDITOR, false); }

    public void setWideEditor(boolean wide) {
        prefs.edit().putBoolean(KEY_WIDE_EDITOR, wide).apply();
    }

    // ---------------------------------------------------------------- editing

    /** Debounce before an edit is written to disk, in milliseconds. */
    public int autosaveDelayMs() { return prefs.getInt(KEY_AUTOSAVE_MS, 700); }

    public void setAutosaveDelayMs(int ms) {
        prefs.edit().putInt(KEY_AUTOSAVE_MS, Math.max(200, Math.min(5000, ms))).apply();
    }

    public boolean haptics() { return prefs.getBoolean(KEY_HAPTICS, true); }

    public void setHaptics(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply();
    }

    public boolean confirmDelete() { return prefs.getBoolean(KEY_CONFIRM_DELETE, true); }

    public void setConfirmDelete(boolean enabled) {
        prefs.edit().putBoolean(KEY_CONFIRM_DELETE, enabled).apply();
    }

    public int trashRetentionDays() { return prefs.getInt(KEY_TRASH_DAYS, 30); }

    public void setTrashRetentionDays(int days) {
        prefs.edit().putInt(KEY_TRASH_DAYS, Math.max(1, Math.min(365, days))).apply();
    }

    // ---------------------------------------------------------------- browsing

    public int sortMode() { return prefs.getInt(KEY_SORT, SORT_MODIFIED); }

    public void setSortMode(int mode) { prefs.edit().putInt(KEY_SORT, mode).apply(); }

    public boolean sortDescending() { return prefs.getBoolean(KEY_SORT_DESC, true); }

    public void setSortDescending(boolean descending) {
        prefs.edit().putBoolean(KEY_SORT_DESC, descending).apply();
    }

    public boolean showArchived() { return prefs.getBoolean(KEY_SHOW_ARCHIVED, false); }

    public void setShowArchived(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_ARCHIVED, show).apply();
    }

    public boolean groupFolders() { return prefs.getBoolean(KEY_GROUP_FOLDERS, true); }

    public void setGroupFolders(boolean group) {
        prefs.edit().putBoolean(KEY_GROUP_FOLDERS, group).apply();
    }

    public String lastFolder() { return prefs.getString(KEY_LAST_FOLDER, ""); }

    public void setLastFolder(String folder) {
        prefs.edit().putString(KEY_LAST_FOLDER, folder == null ? "" : folder).apply();
    }

    // ---------------------------------------------------------------- split view

    /**
     * Fraction of the editor given to the source pane. Remembered per editor,
     * because a split the user has adjusted should stay adjusted.
     */
    public float texSplitRatio() {
        return prefs.getFloat(KEY_TEX_SPLIT_RATIO, 0.5f);
    }

    public void setTexSplitRatio(float ratio) {
        prefs.edit().putFloat(KEY_TEX_SPLIT_RATIO, ratio).apply();
    }

    public boolean texPreviewOpen() {
        return prefs.getBoolean(KEY_TEX_PREVIEW_OPEN, false);
    }

    public void setTexPreviewOpen(boolean open) {
        prefs.edit().putBoolean(KEY_TEX_PREVIEW_OPEN, open).apply();
    }

    public float noteSplitRatio() {
        return prefs.getFloat(KEY_NOTE_SPLIT_RATIO, 0.5f);
    }

    public void setNoteSplitRatio(float ratio) {
        prefs.edit().putFloat(KEY_NOTE_SPLIT_RATIO, ratio).apply();
    }

    public boolean notePreviewOpen() {
        return prefs.getBoolean(KEY_NOTE_PREVIEW_OPEN, false);
    }

    public void setNotePreviewOpen(boolean open) {
        prefs.edit().putBoolean(KEY_NOTE_PREVIEW_OPEN, open).apply();
    }

    // ---------------------------------------------------------------- graph

    /** Tags stay out of the graph until explicitly enabled in its filter menu. */
    public boolean graphShowTags() { return prefs.getBoolean(KEY_GRAPH_TAGS, false); }

    public void setGraphShowTags(boolean show) {
        prefs.edit().putBoolean(KEY_GRAPH_TAGS, show).apply();
    }

    public boolean graphShowLabels() { return prefs.getBoolean(KEY_GRAPH_LABELS, true); }

    public void setGraphShowLabels(boolean show) {
        prefs.edit().putBoolean(KEY_GRAPH_LABELS, show).apply();
    }

    public boolean graphShowOrphans() { return prefs.getBoolean(KEY_GRAPH_ORPHANS, true); }

    public void setGraphShowOrphans(boolean show) {
        prefs.edit().putBoolean(KEY_GRAPH_ORPHANS, show).apply();
    }

    // ---------------------------------------------------------------- session

    public boolean onboarded() { return prefs.getBoolean(KEY_ONBOARDED, false); }

    public void setOnboarded(boolean done) {
        prefs.edit().putBoolean(KEY_ONBOARDED, done).apply();
    }

    public String lastNoteId() { return prefs.getString(KEY_LAST_NOTE, null); }

    public void setLastNoteId(String id) {
        prefs.edit().putString(KEY_LAST_NOTE, id).apply();
    }

    /** Cursor state is scoped by note id so every document resumes independently. */
    public CursorState cursor(String noteId) {
        if (noteId == null) return null;
        String value = prefs.getString("cursor:" + noteId, null);
        if (value == null) return null;
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) return null;
        try {
            return new CursorState("raw".equals(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCursor(String noteId, boolean raw, int block, int offset) {
        if (noteId == null) return;
        prefs.edit().putString("cursor:" + noteId,
                (raw ? "raw" : "block") + "|" + Math.max(0, block)
                        + "|" + Math.max(0, offset)).apply();
    }

    public static final class CursorState {
        public final boolean raw;
        public final int block;
        public final int offset;

        CursorState(boolean raw, int block, int offset) {
            this.raw = raw;
            this.block = block;
            this.offset = offset;
        }
    }

    /** Records navigation without duplicating the current entry or stale forward entries. */
    public void recordNavigation(String noteId) {
        if (noteId == null || noteId.trim().isEmpty()) return;
        List<String> history = navigationHistory();
        int index = navigationIndex(history.size());
        if (index >= 0 && index < history.size() && noteId.equals(history.get(index))) return;
        while (history.size() > index + 1) history.remove(history.size() - 1);
        history.add(noteId);
        while (history.size() > 40) history.remove(0);
        writeNavigation(history, history.size() - 1);
    }

    public String navigateNote(int delta) {
        List<String> history = navigationHistory();
        if (history.isEmpty()) return null;
        int target = navigationIndex(history.size()) + delta;
        if (target < 0 || target >= history.size()) return null;
        prefs.edit().putInt(KEY_NAV_INDEX, target).apply();
        return history.get(target);
    }

    /** A snapshot of the opened-note trail, in the order it was visited. */
    public List<String> navigationEntries() {
        return new ArrayList<>(navigationHistory());
    }

    /** The current position in {@link #navigationEntries()}, or {@code -1} when empty. */
    public int navigationPosition() {
        List<String> history = navigationHistory();
        return navigationIndex(history.size());
    }

    /** Moves directly to an entry selected from the opened-note history. */
    public String navigateToHistoryIndex(int index) {
        List<String> history = navigationHistory();
        if (index < 0 || index >= history.size()) return null;
        prefs.edit().putInt(KEY_NAV_INDEX, index).apply();
        return history.get(index);
    }

    public boolean canNavigate(int delta) {
        List<String> history = navigationHistory();
        if (history.isEmpty()) return false;
        int target = navigationIndex(history.size()) + delta;
        return target >= 0 && target < history.size();
    }

    private List<String> navigationHistory() {
        String raw = prefs.getString(KEY_NAV_HISTORY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String id : raw.split("\\n")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private int navigationIndex(int size) {
        if (size == 0) return -1;
        return Math.max(0, Math.min(size - 1, prefs.getInt(KEY_NAV_INDEX, size - 1)));
    }

    private void writeNavigation(List<String> history, int index) {
        StringBuilder raw = new StringBuilder();
        for (String id : history) {
            if (raw.length() > 0) raw.append('\n');
            raw.append(id);
        }
        prefs.edit().putString(KEY_NAV_HISTORY, raw.toString())
                .putInt(KEY_NAV_INDEX, index).apply();
    }

    /** Recently opened note ids, most recent first. */
    public List<String> recents() {
        String raw = prefs.getString(KEY_RECENTS, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("\n")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    public void pushRecent(String noteId) {
        if (noteId == null || noteId.isEmpty()) return;
        List<String> recents = recents();
        recents.remove(noteId);
        recents.add(0, noteId);
        while (recents.size() > 20) recents.remove(recents.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recents.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(recents.get(i));
        }
        prefs.edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    public void forgetRecent(String noteId) {
        List<String> recents = recents();
        if (!recents.remove(noteId)) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recents.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(recents.get(i));
        }
        prefs.edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    public void clearRecents() { prefs.edit().remove(KEY_RECENTS).apply(); }

    // ---------------------------------------------------------------- folder sync

    public String syncTreeUri() { return prefs.getString(KEY_SYNC_TREE, null); }

    public void setSyncTreeUri(String uri) {
        SharedPreferences.Editor edit = prefs.edit();
        if (uri == null || uri.isEmpty()) edit.remove(KEY_SYNC_TREE);
        else edit.putString(KEY_SYNC_TREE, uri);
        edit.apply();
    }

    public long lastSyncMillis() { return prefs.getLong(KEY_SYNC_LAST, 0L); }

    public void setLastSyncMillis(long millis) {
        prefs.edit().putLong(KEY_SYNC_LAST, millis).apply();
    }

    // ---------------------------------------------------------------- Git sync

    public GitSyncConfig gitSyncConfig() {
        String remote = prefs.getString(KEY_GIT_REMOTE, null);
        if (remote == null || remote.isEmpty()) return null;
        return new GitSyncConfig(remote,
                prefs.getString(KEY_GIT_BRANCH, "main"),
                prefs.getString(KEY_GIT_USERNAME, ""),
                prefs.getString(KEY_GIT_AUTHOR, "Shard"),
                prefs.getString(KEY_GIT_EMAIL, "shard@localhost"));
    }

    /** Authentication secrets are intentionally never written to preferences. */
    public void setGitSyncConfig(GitSyncConfig config) {
        prefs.edit()
                .putString(KEY_GIT_REMOTE, config.getRemoteUrl())
                .putString(KEY_GIT_BRANCH, config.getBranch())
                .putString(KEY_GIT_USERNAME, config.getUsername())
                .putString(KEY_GIT_AUTHOR, config.getAuthorName())
                .putString(KEY_GIT_EMAIL, config.getAuthorEmail())
                .apply();
    }

    public void clearGitSyncConfig() {
        prefs.edit().remove(KEY_GIT_REMOTE).remove(KEY_GIT_BRANCH)
                .remove(KEY_GIT_USERNAME).remove(KEY_GIT_AUTHOR).remove(KEY_GIT_EMAIL)
                .remove(KEY_GIT_LAST).apply();
    }

    public long lastGitSyncMillis() { return prefs.getLong(KEY_GIT_LAST, 0L); }

    public void setLastGitSyncMillis(long millis) {
        prefs.edit().putLong(KEY_GIT_LAST, millis).apply();
    }

    public long lastAutoBackupMillis() { return prefs.getLong(KEY_AUTO_BACKUP_LAST, 0L); }

    public void setLastAutoBackupMillis(long millis) {
        prefs.edit().putLong(KEY_AUTO_BACKUP_LAST, millis).apply();
    }

    public String lastImportUri() { return prefs.getString(KEY_LAST_IMPORT_URI, null); }

    public void setLastImportUri(String uri) {
        prefs.edit().putString(KEY_LAST_IMPORT_URI, uri).apply();
    }

    public String lastExportUri() { return prefs.getString(KEY_LAST_EXPORT_URI, null); }

    public void setLastExportUri(String uri) {
        prefs.edit().putString(KEY_LAST_EXPORT_URI, uri).apply();
    }

    public boolean hasPendingCrashReport() { return prefs.getBoolean(KEY_PENDING_CRASH, false); }

    public void setPendingCrashReport(boolean value) {
        prefs.edit().putBoolean(KEY_PENDING_CRASH, value).apply();
    }

    // ------------------------------------------------------------ cloud mirror

    /**
     * Which remote the vault mirrors to. Stored by
     * {@link com.ccs.shard.core.cloud.CloudProvider#id()} rather than by
     * ordinal, so the enum can be reordered without repointing anyone's sync.
     */
    public com.ccs.shard.core.cloud.CloudProvider cloudProvider() {
        return com.ccs.shard.core.cloud.CloudProvider.fromId(
                prefs.getString(KEY_CLOUD_PROVIDER, null));
    }

    public void setCloudProvider(com.ccs.shard.core.cloud.CloudProvider provider) {
        SharedPreferences.Editor edit = prefs.edit();
        if (provider == null || provider == com.ccs.shard.core.cloud.CloudProvider.NONE) {
            edit.remove(KEY_CLOUD_PROVIDER);
        } else {
            edit.putString(KEY_CLOUD_PROVIDER, provider.id());
        }
        edit.apply();
    }

    /**
     * The signed-in account, used to ask the auth library for a token.
     * An account name is not a secret; the token it yields is, and that one is
     * never written here - same rule as {@link #setGitSyncConfig}.
     */
    public String cloudAccount() { return prefs.getString(KEY_CLOUD_ACCOUNT, null); }

    public void setCloudAccount(String account) {
        SharedPreferences.Editor edit = prefs.edit();
        if (account == null || account.isEmpty()) edit.remove(KEY_CLOUD_ACCOUNT);
        else edit.putString(KEY_CLOUD_ACCOUNT, account);
        edit.apply();
    }

    /** Name of the remote folder the vault is mirrored into. */
    public String cloudFolderName() {
        String name = prefs.getString(KEY_CLOUD_FOLDER, null);
        return name == null || name.trim().isEmpty() ? "Shard" : name.trim();
    }

    public void setCloudFolderName(String name) {
        SharedPreferences.Editor edit = prefs.edit();
        if (name == null || name.trim().isEmpty()) edit.remove(KEY_CLOUD_FOLDER);
        else edit.putString(KEY_CLOUD_FOLDER, name.trim());
        edit.apply();
    }

    public long cloudLastSyncMillis() { return prefs.getLong(KEY_CLOUD_LAST, 0L); }

    public void setCloudLastSyncMillis(long millis) {
        prefs.edit().putLong(KEY_CLOUD_LAST, millis).apply();
    }

    /**
     * The reason name of the last failure, or {@code null} when the last drain
     * was clean. Only the reason is kept - never a provider message, which can
     * echo note titles.
     */
    public String cloudLastError() { return prefs.getString(KEY_CLOUD_ERROR, null); }

    public void setCloudLastError(String reason) {
        SharedPreferences.Editor edit = prefs.edit();
        if (reason == null || reason.isEmpty()) edit.remove(KEY_CLOUD_ERROR);
        else edit.putString(KEY_CLOUD_ERROR, reason);
        edit.apply();
    }

    public void resetToDefaults() {
        prefs.edit()
                .remove(KEY_THEME).remove(KEY_ACCENT).remove(KEY_DYNAMIC)
                .remove(KEY_FONT_SIZE).remove(KEY_FONT_FAMILY).remove(KEY_WIDE_EDITOR)
                .remove(KEY_AUTOSAVE_MS).remove(KEY_SORT).remove(KEY_SORT_DESC)
                .remove(KEY_SHOW_ARCHIVED).remove(KEY_GROUP_FOLDERS)
                .remove(KEY_LAST_FOLDER)
                .remove(KEY_CONFIRM_DELETE).remove(KEY_HAPTICS)
                .remove(KEY_GRAPH_TAGS).remove(KEY_GRAPH_LABELS).remove(KEY_GRAPH_ORPHANS)
                .remove(KEY_TRASH_DAYS)
                .remove(KEY_SYNC_TREE).remove(KEY_SYNC_LAST)
                .remove(KEY_GIT_REMOTE).remove(KEY_GIT_BRANCH).remove(KEY_GIT_USERNAME)
                .remove(KEY_GIT_AUTHOR).remove(KEY_GIT_EMAIL).remove(KEY_GIT_LAST)
                .remove(KEY_LAST_IMPORT_URI).remove(KEY_LAST_EXPORT_URI)
                .remove(KEY_NAV_HISTORY).remove(KEY_NAV_INDEX)
                .remove(KEY_CLOUD_PROVIDER).remove(KEY_CLOUD_ACCOUNT)
                .remove(KEY_CLOUD_FOLDER).remove(KEY_CLOUD_LAST).remove(KEY_CLOUD_ERROR)
                .apply();
    }
}
