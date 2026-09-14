package com.ccs.shard.core;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Privacy-preserving crash capture: reports stay local until the user shares them. */
public final class CrashReporter implements Thread.UncaughtExceptionHandler {

    private static final int KEEP_REPORTS = 10;
    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashReporter(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context.getApplicationContext();
        this.previous = previous;
    }

    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashReporter) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashReporter(context, current));
    }

    @Override public void uncaughtException(Thread thread, Throwable error) {
        try {
            writeReport(context, thread, error);
            new Prefs(context).setPendingCrashReport(true);
        } catch (Throwable ignored) { }
        if (previous != null) previous.uncaughtException(thread, error);
        else android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void writeReport(Context context, Thread thread, Throwable error)
            throws java.io.IOException {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        StringBuilder report = new StringBuilder(2048);
        report.append("Shard crash report\n")
                .append("Time: ").append(new Date()).append('\n')
                .append("Version: ").append(com.ccs.shard.BuildConfig.VERSION_NAME)
                .append(" (").append(com.ccs.shard.BuildConfig.VERSION_CODE).append(")\n")
                .append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
                .append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n')
                .append("Thread: ").append(thread == null ? "unknown" : thread.getName())
                .append("\n\n").append(trace);
        File dir = directory(context);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        NoteFile.writeAtomic(new File(dir, "crash-" + stamp + ".txt"), report.toString());
        prune(dir);
    }

    public static List<File> reports(Context context) {
        File[] files = directory(context).listFiles(file ->
                file.isFile() && file.getName().endsWith(".txt"));
        if (files == null) return new java.util.ArrayList<>();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new java.util.ArrayList<>(Arrays.asList(files));
    }

    public static File latest(Context context) {
        List<File> reports = reports(context);
        return reports.isEmpty() ? null : reports.get(0);
    }

    public static void clear(Context context) {
        for (File report : reports(context)) {
            //noinspection ResultOfMethodCallIgnored
            report.delete();
        }
        new Prefs(context).setPendingCrashReport(false);
    }

    private static File directory(Context context) {
        File dir = new File(context.getFilesDir(), "crash-reports");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    private static void prune(File dir) {
        List<File> files = reportsFrom(dir);
        for (int i = KEEP_REPORTS; i < files.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            files.get(i).delete();
        }
    }

    private static List<File> reportsFrom(File dir) {
        File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".txt"));
        if (files == null) return new java.util.ArrayList<>();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new java.util.ArrayList<>(Arrays.asList(files));
    }
}
