package com.ccs.shard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Configurable note/task widget with permanent quick-create shortcuts. */
public final class QuickNoteWidget extends AppWidgetProvider {

    private static final String PREFS = "shard_widgets";

    public static void configure(Context context, int widgetId, String mode,
                                 String targetId, String label) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode_" + widgetId, mode)
                .putString("target_" + widgetId, targetId)
                .putString("label_" + widgetId, label)
                .apply();
        update(context, AppWidgetManager.getInstance(context), widgetId);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit();
        for (int id : ids) {
            edit.remove("mode_" + id).remove("target_" + id).remove("label_" + id);
        }
        edit.apply();
    }

    private static void update(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString("mode_" + widgetId, "new");
        String target = prefs.getString("target_" + widgetId, null);
        String label = prefs.getString("label_" + widgetId,
                context.getString(R.string.new_note_widget));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_note);
        views.setTextViewText(R.id.widgetTitle, label);
        views.setTextViewText(R.id.widgetSubtitle, subtitle(context, mode));
        views.setOnClickPendingIntent(R.id.widgetRoot,
                pending(context, rootIntent(context, mode, target), widgetId * 10));
        views.setOnClickPendingIntent(R.id.widgetNewNote,
                pending(context, newNoteIntent(context), widgetId * 10 + 1));
        views.setOnClickPendingIntent(R.id.widgetDaily,
                pending(context, new Intent(context, DailyNoteActivity.class), widgetId * 10 + 2));
        views.setOnClickPendingIntent(R.id.widgetTasks,
                pending(context, new Intent(context, TasksActivity.class), widgetId * 10 + 3));
        manager.updateAppWidget(widgetId, views);
    }

    private static Intent rootIntent(Context context, String mode, String target) {
        if ("daily".equals(mode)) return new Intent(context, DailyNoteActivity.class);
        if ("tasks".equals(mode)) return new Intent(context, TasksActivity.class);
        if (("note".equals(mode) || "task".equals(mode)) && target != null) {
            return new Intent(context, NoteEditorActivity.class)
                    .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, target);
        }
        return newNoteIntent(context);
    }

    private static Intent newNoteIntent(Context context) {
        return new Intent(context, NoteEditorActivity.class)
                .putExtra(NoteEditorActivity.EXTRA_CREATE_IN_FOLDER, "");
    }

    private static String subtitle(Context context, String mode) {
        if ("daily".equals(mode)) return context.getString(R.string.nav_daily_note);
        if ("tasks".equals(mode) || "task".equals(mode)) {
            return context.getString(R.string.nav_tasks);
        }
        if ("note".equals(mode)) return context.getString(R.string.widget_specific_note);
        return context.getString(R.string.app_name);
    }

    private static PendingIntent pending(Context context, Intent intent, int request) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
