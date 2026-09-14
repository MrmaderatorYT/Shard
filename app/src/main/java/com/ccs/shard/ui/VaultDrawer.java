package com.ccs.shard.ui;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.CanvasActivity;
import com.ccs.shard.CollectionsActivity;
import com.ccs.shard.DocumentationActivity;
import com.ccs.shard.GraphActivity;
import com.ccs.shard.R;
import com.ccs.shard.SettingsActivity;
import com.ccs.shard.TasksActivity;
import com.ccs.shard.TrashActivity;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.VaultRepository;

/**
 * Builds the navigation drawer's contents.
 *
 * <p>This is view construction, not screen logic, and it was two hundred lines
 * of it sitting in the middle of the vault browser. Destinations that are just
 * another screen are started from here; anything that changes what the browser
 * is showing goes back through {@link Host}.
 */
public final class VaultDrawer {

    /** The browser state this drawer can change. */
    public interface Host {
        void showAllNotes();
        void showFavourites();
        void showRecent();
        void openToday();
        void showCalendar();
        void showTagPicker();
        /** {@code ""} selects the vault root. */
        void openFolder(String path);
        /** Receives a note dropped on a folder inside the drawer. */
        void moveNoteToFolder(String noteId, String folderPath);
        void promptNewFolder();
        void showImportMenu(View anchor);
        void backupVault();
        /** Closes the drawer before an action takes effect. */
        void closeDrawer();
    }

    private final BaseActivity activity;
    private final VaultRepository repo;
    private final LinearLayout container;
    private final Host host;

    public VaultDrawer(BaseActivity activity, VaultRepository repo,
                       LinearLayout container, Host host) {
        this.activity = activity;
        this.repo = repo;
        this.container = container;
        this.host = host;
    }

    /** Rebuilds every row; cheap enough to call whenever the vault changes. */
    public void rebuild() {
        if (container == null) return;
        container.removeAllViews();

        container.addView(header());

        container.addView(section(R.string.nav_library));
        container.addView(row(R.drawable.ic_notes, R.string.nav_all_notes,
                String.valueOf(repo.index().size()), host::showAllNotes));
        container.addView(row(R.drawable.ic_star_outline, R.string.nav_favourites,
                null, host::showFavourites));
        container.addView(row(R.drawable.ic_recent, R.string.nav_recent,
                null, host::showRecent));
        container.addView(row(R.drawable.ic_block_date, R.string.nav_daily_note,
                null, host::openToday));
        container.addView(row(R.drawable.ic_recent, R.string.nav_calendar,
                null, host::showCalendar));
        container.addView(row(R.drawable.ic_checkbox, R.string.nav_tasks,
                null, () -> open(TasksActivity.class)));
        container.addView(row(R.drawable.ic_filter, R.string.nav_collections,
                null, () -> open(CollectionsActivity.class)));
        container.addView(row(R.drawable.ic_tag, R.string.nav_tags,
                String.valueOf(repo.index().allTags().size()), host::showTagPicker));
        container.addView(row(R.drawable.ic_graph, R.string.nav_graph,
                null, () -> open(GraphActivity.class)));
        container.addView(row(R.drawable.ic_canvas, R.string.nav_canvas, null, () -> {
            host.closeDrawer();
            CanvasActivity.start(activity);
        }));

        container.addView(section(R.string.nav_folders));
        container.addView(folderRow(R.drawable.ic_folder_open, R.string.vault_root,
                null, "", () -> host.openFolder("")));
        addFolderTree();
        container.addView(row(R.drawable.ic_add, R.string.nav_new_folder,
                null, host::promptNewFolder));

        container.addView(section(R.string.nav_vault));
        container.addView(row(R.drawable.ic_import, R.string.nav_import,
                null, () -> host.showImportMenu(container)));
        container.addView(row(R.drawable.ic_backup, R.string.nav_export_vault,
                null, host::backupVault));
        container.addView(row(R.drawable.ic_trash, R.string.nav_trash,
                null, () -> open(TrashActivity.class)));
        container.addView(row(R.drawable.ic_settings, R.string.nav_settings,
                null, () -> open(SettingsActivity.class)));

        container.addView(section(R.string.nav_help));
        container.addView(row(R.drawable.ic_info, R.string.nav_docs_md_tex,
                null, () -> open(DocumentationActivity.class)));
    }

    /** Folder rows, indented by depth so nesting is visible at a glance. */
    private void addFolderTree() {
        for (final String path : repo.index().allFolders()) {
            int depth = 0;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') depth++;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            View view = folderRow(R.drawable.ic_folder, name, null, path,
                    () -> host.openFolder(path));
            view.setPadding(Ui.dp(activity, 20 + depth * 14), view.getPaddingTop(),
                    view.getPaddingRight(), view.getPaddingBottom());
            container.addView(view);
        }
    }

    private void open(Class<?> screen) {
        host.closeDrawer();
        activity.startActivity(new Intent(activity, screen));
    }

    // ---------------------------------------------------------------- pieces

    private View header() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(header, 18, 4, 20, 16);

        ImageView mark = new ImageView(activity);
        mark.setImageResource(R.drawable.ic_shard_mark);
        LinearLayout.LayoutParams markParams =
                new LinearLayout.LayoutParams(Ui.dp(activity, 34), Ui.dp(activity, 34));
        markParams.rightMargin = Ui.dp(activity, 12);
        mark.setLayoutParams(markParams);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(activity);
        name.setText(R.string.app_name);
        name.setTextSize(20f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurface));
        texts.addView(name);

        TextView subtitle = new TextView(activity);
        subtitle.setTextSize(11f);
        subtitle.setTextColor(Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subtitle.setText(activity.getString(R.string.storage_at,
                repo.vault().displayPath()));
        texts.addView(subtitle);

        header.addView(texts);
        return header;
    }

    private View section(int labelRes) {
        TextView view = new TextView(activity);
        view.setText(labelRes);
        view.setTextSize(11f);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        view.setTextColor(Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        Ui.setPaddingDp(view, 20, 14, 20, 6);
        return view;
    }

    private View row(int iconRes, int labelRes, String badge, Runnable action) {
        return row(iconRes, activity.getString(labelRes), badge, action);
    }

    private View row(int iconRes, String label, String badge, final Runnable action) {
        int onSurface = Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurface);
        int variant = Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 44));
        Ui.setPaddingDp(row, 20, 4, 16, 4);
        // A RippleDrawable with neither content nor mask is "unbounded": its wave
        // can paint across the parent ScrollView and makes the whole drawer look
        // like one giant button. A transparent rounded content layer clips the
        // animation to this row, like a ListView item selector.
        row.setBackground(Ui.rippleRect(activity, Ui.withAlpha(onSurface, 0.10f),
                Ui.dp(activity, 9), 0x00000000));
        row.setOnClickListener(v -> action.run());
        row.setFocusable(true);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(variant);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(Ui.dp(activity, 19), Ui.dp(activity, 19));
        iconParams.rightMargin = Ui.dp(activity, 16);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(14.5f);
        text.setTextColor(onSurface);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);

        if (badge != null) {
            TextView count = new TextView(activity);
            count.setText(badge);
            count.setTextSize(11.5f);
            count.setTextColor(variant);
            row.addView(count);
        }
        return row;
    }

    private View folderRow(int iconRes, int labelRes, String badge, String folderPath,
                           Runnable action) {
        return folderRow(iconRes, activity.getString(labelRes), badge, folderPath, action);
    }

    private View folderRow(int iconRes, String label, String badge, final String folderPath,
                           Runnable action) {
        View row = row(iconRes, label, badge, action);
        row.setOnDragListener((v, event) -> {
            ClipDescription description = event.getClipDescription();
            boolean noteDrag = description != null
                    && "shard-note".contentEquals(description.getLabel());
            if (!noteDrag) return false;
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    v.animate().scaleX(1.02f).scaleY(1.02f).alpha(0.72f)
                            .setDuration(100L).start();
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120L).start();
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120L).start();
                    ClipData data = event.getClipData();
                    if (data != null && data.getItemCount() > 0) {
                        host.moveNoteToFolder(data.getItemAt(0)
                                .coerceToText(activity).toString(), folderPath);
                    }
                    return true;
                default:
                    return true;
            }
        });
        return row;
    }
}
