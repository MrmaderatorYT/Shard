package com.ccs.shard.core;

import android.content.Context;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.util.Collection;
import java.util.Locale;

/**
 * Git transport for the vault.
 *
 * <p>Git metadata and its work tree stay in app-private storage. A small local
 * tree synchronizer exchanges files with the live vault, so editor indexing,
 * exports and backups never walk through object packs or lock files.
 */
public final class GitSyncService {

    private final Context context;
    private final VaultRepository repository;
    private final File workspace;
    private final File stateFile;

    public GitSyncService(Context context, VaultRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.workspace = new File(this.context.getFilesDir(), "git-sync/repository");
        this.stateFile = new File(this.context.getFilesDir(), "git-sync/state.tsv");
    }

    /** Blocking network and disk operation; call through {@link Io#load}. */
    public GitSyncResult sync(GitSyncConfig config, GitCredentials credentials,
                              boolean push) throws Exception {
        if (config == null || !config.isValid()) {
            throw new GitSyncException(GitSyncException.Reason.REPOSITORY);
        }
        new AutoBackupManager(context, repository).backupIfDue(true);
        CredentialsProvider authentication = credentialsProvider(credentials);
        GitSyncResult result = new GitSyncResult();

        try (Git git = openOrCreate(config, authentication)) {
            FileTreeSync treeSync = new FileTreeSync(repository.vault().root(),
                    workspace, stateFile);
            merge(treeSync.sync(), result);
            result.committed |= commitIfNeeded(git, config);

            Ref remoteBranch = fetch(git, config, authentication);
            if (remoteBranch != null) {
                MergeResult merge = git.merge().include(remoteBranch)
                        .setStrategy(MergeStrategy.RECURSIVE).call();
                if (!merge.getMergeStatus().isSuccessful()) {
                    int conflicts = merge.getConflicts() == null ? 1 : merge.getConflicts().size();
                    result.conflicts += Math.max(1, conflicts);
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
                    throw new GitSyncException(GitSyncException.Reason.CONFLICT);
                }
            }

            merge(treeSync.sync(), result);
            result.committed |= commitIfNeeded(git, config);
            if (push) {
                push(git, config, authentication);
                result.remoteUpdated = true;
            }
            result.revision = revision(git.getRepository());
        } catch (GitSyncException error) {
            throw error;
        } catch (org.eclipse.jgit.api.errors.TransportException error) {
            throw classifyTransport(error);
        } catch (org.eclipse.jgit.errors.TransportException error) {
            throw classifyTransport(error);
        } catch (Throwable error) {
            throw new GitSyncException(GitSyncException.Reason.REPOSITORY, error);
        } finally {
            // FileTreeSync may have resolved a file before a later network failure.
            // Keep the in-memory index aligned with the actual vault in every outcome.
            repository.refresh();
        }
        return result;
    }

    /** Clears only the disposable Git mirror; vault files remain untouched. */
    public void clearWorkspace() {
        deleteTree(workspace);
        //noinspection ResultOfMethodCallIgnored
        stateFile.delete();
    }

    private Git openOrCreate(GitSyncConfig config, CredentialsProvider credentials)
            throws Exception {
        File gitDir = new File(workspace, Constants.DOT_GIT);
        if (gitDir.isDirectory()) {
            Git git = Git.open(workspace);
            configureRemote(git.getRepository(), config);
            return git;
        }

        deleteTree(workspace);
        File parent = workspace.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("Cannot create Git workspace");
        }

        Collection<Ref> refs = Git.lsRemoteRepository()
                .setRemote(config.getRemoteUrl())
                .setHeads(true)
                .setTags(false)
                .setCredentialsProvider(credentials)
                .call();
        boolean hasHeads = false;
        boolean hasBranch = false;
        String wanted = Constants.R_HEADS + config.getBranch();
        for (Ref ref : refs) {
            if (ref.getName().startsWith(Constants.R_HEADS)) hasHeads = true;
            if (wanted.equals(ref.getName())) hasBranch = true;
        }
        if (hasHeads && !hasBranch) {
            throw new GitSyncException(GitSyncException.Reason.BRANCH);
        }

        if (hasBranch) {
            return Git.cloneRepository()
                    .setURI(config.getRemoteUrl())
                    .setBranch(wanted)
                    .setDirectory(workspace)
                    .setCredentialsProvider(credentials)
                    .call();
        }

        Git git = Git.init().setDirectory(workspace).call();
        RefUpdate head = git.getRepository().updateRef(Constants.HEAD);
        head.link(wanted);
        configureRemote(git.getRepository(), config);
        return git;
    }

    private static void configureRemote(Repository repository, GitSyncConfig config)
            throws Exception {
        StoredConfig stored = repository.getConfig();
        stored.setString("remote", "origin", "url", config.getRemoteUrl());
        stored.setString("remote", "origin", "fetch",
                "+refs/heads/*:refs/remotes/origin/*");
        stored.setString("branch", config.getBranch(), "remote", "origin");
        stored.setString("branch", config.getBranch(), "merge",
                Constants.R_HEADS + config.getBranch());
        stored.save();
    }

    private static Ref fetch(Git git, GitSyncConfig config,
                             CredentialsProvider credentials) throws Exception {
        git.fetch().setRemote("origin").setCredentialsProvider(credentials).call();
        return git.getRepository().findRef(
                Constants.R_REMOTES + "origin/" + config.getBranch());
    }

    private static boolean commitIfNeeded(Git git, GitSyncConfig config) throws Exception {
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        if (git.status().call().isClean()) return false;
        String timestamp = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", Locale.ROOT).format(new java.util.Date());
        git.commit().setMessage("Shard sync " + timestamp)
                .setAuthor(config.getAuthorName(), config.getAuthorEmail())
                .setCommitter(config.getAuthorName(), config.getAuthorEmail())
                .call();
        return true;
    }

    private static void push(Git git, GitSyncConfig config,
                             CredentialsProvider credentials) throws Exception {
        Iterable<PushResult> batches = git.push().setRemote("origin")
                .setCredentialsProvider(credentials)
                .setRefSpecs(new RefSpec(Constants.R_HEADS + config.getBranch()
                        + ":" + Constants.R_HEADS + config.getBranch()))
                .call();
        for (PushResult batch : batches) {
            for (RemoteRefUpdate update : batch.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK
                        && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new GitSyncException(GitSyncException.Reason.REPOSITORY);
                }
            }
        }
    }

    private static CredentialsProvider credentialsProvider(GitCredentials credentials) {
        if (credentials == null || credentials.isEmpty()) return null;
        String username = credentials.getUsername().isEmpty()
                ? "git" : credentials.getUsername();
        return new UsernamePasswordCredentialsProvider(username, credentials.getToken());
    }

    private static void merge(FileTreeSync.Result source, GitSyncResult target) {
        target.pulled += source.fromRight;
        target.pushed += source.toRight;
        target.conflicts += source.conflicts;
    }

    private static String revision(Repository repository) throws Exception {
        org.eclipse.jgit.lib.ObjectId head = repository.resolve(Constants.HEAD);
        return head == null ? "" : head.abbreviate(7).name();
    }

    private static GitSyncException classifyTransport(Throwable error) {
        String message = error.getMessage() == null ? ""
                : error.getMessage().toLowerCase(Locale.ROOT);
        GitSyncException.Reason reason = message.contains("auth")
                || message.contains("401") || message.contains("403")
                || message.contains("not authorized")
                ? GitSyncException.Reason.AUTHENTICATION
                : GitSyncException.Reason.NETWORK;
        return new GitSyncException(reason, error);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
