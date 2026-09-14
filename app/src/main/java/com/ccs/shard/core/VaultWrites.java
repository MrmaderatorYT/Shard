package com.ccs.shard.core;

import android.util.Log;

import java.io.File;

/**
 * A single place to learn that the vault changed on disk.
 *
 * <p>Exists so the cloud mirror needs two hooks instead of twenty. Content
 * writes are reported from {@link NoteFile#writeAtomic}, the one primitive
 * every note, canvas and link rewrite funnels through - including the paths
 * that bypass {@link VaultRepository} entirely, like {@link CanvasStore}.
 * Structural changes (rename, move, trash, folder operations, bulk edits) are
 * reported as a single "something moved" signal from
 * {@code VaultRepository.notifyChanged}, because they are carried out with
 * bare {@code renameTo} and {@code delete} calls scattered across a dozen
 * methods, and an observer that has to be remembered at each of them is an
 * observer that will be forgotten at one of them.
 *
 * <p>The observer is told about every write, sidecars included; deciding what
 * is worth acting on is the observer's job.
 */
public final class VaultWrites {

    private static final String TAG = "VaultWrites";

    public interface Observer {
        /** A file was successfully written. Called on the writing thread. */
        void onVaultFileWritten(File file);

        /**
         * Files may have been renamed, moved or removed. Carries no payload:
         * the callers do not agree on what they changed, so the observer has
         * to go and look.
         */
        void onVaultStructureChanged();
    }

    private static volatile Observer observer;

    private VaultWrites() {}

    /** Registers the single observer, replacing any previous one. */
    public static void setObserver(Observer next) {
        observer = next;
    }

    static void notifyWritten(File file) {
        Observer local = observer;
        if (local == null || file == null) return;
        try {
            local.onVaultFileWritten(file);
        } catch (Throwable t) {
            // A misbehaving mirror must never fail a note save.
            Log.w(TAG, "write observer failed", t);
        }
    }

    static void notifyStructureChanged() {
        Observer local = observer;
        if (local == null) return;
        try {
            local.onVaultStructureChanged();
        } catch (Throwable t) {
            Log.w(TAG, "structure observer failed", t);
        }
    }
}
