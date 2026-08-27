package io.jenkins.plugins.ranchermanager;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRepositoryFilesTest {

    private static final String LOOPBACK_REPO = "http://127.0.0.1/values.git";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearHooks() {
        GitRepositoryFiles.testOverride.set(null);
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    private static GitRepositoryFiles.CloneContext cloneCtx(
            RancherCredentials.GitAuth auth,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener) {
        return new GitRepositoryFiles.CloneContext(auth, workspace, launcher, listener);
    }

    @FunctionalInterface
    private interface CheckoutSeeder {
        void seed(FilePath checkout) throws IOException, InterruptedException;
    }

    private static final class StubGitLauncher extends Launcher.DummyLauncher {
        private final int exitCode;
        private final String stderrText;
        private final CheckoutSeeder seedCheckout;
        private final boolean unix;

        StubGitLauncher(int exitCode, String stderrText, CheckoutSeeder seedCheckout) {
            this(exitCode, stderrText, seedCheckout, true);
        }

        StubGitLauncher(int exitCode, String stderrText, CheckoutSeeder seedCheckout, boolean unix) {
            super(TaskListener.NULL);
            this.exitCode = exitCode;
            this.stderrText = stderrText;
            this.seedCheckout = seedCheckout;
            this.unix = unix;
        }

        @Override
        public boolean isUnix() {
            return unix;
        }

        @Override
        public Proc launch(ProcStarter starter) throws IOException {
            if (stderrText != null && !stderrText.isEmpty() && starter.stderr() != null) {
                starter.stderr().write(stderrText.getBytes(StandardCharsets.UTF_8));
            }
            if (exitCode == 0 && seedCheckout != null && starter.pwd() != null) {
                try {
                    seedCheckout.seed(starter.pwd());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return new Proc() {
                @Override
                public void kill() {
                    throw new UnsupportedOperationException("not used in stub");
                }

                @Override
                public int join() {
                    return exitCode;
                }

                @Override
                public boolean isAlive() {
                    return false;
                }

                @Override
                public InputStream getStdout() {
                    return InputStream.nullInputStream();
                }

                @Override
                public InputStream getStderr() {
                    return InputStream.nullInputStream();
                }

                @Override
                public OutputStream getStdin() {
                    return OutputStream.nullOutputStream();
                }
            };
        }
    }

    private static void allowLoopback() {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
    }

    private static void seedFile(FilePath checkout, String relativePath, String content)
            throws IOException, InterruptedException {
        FilePath file = checkout.child(relativePath);
        FilePath parent = file.getParent();
        if (parent != null) {
            parent.mkdirs();
        }
        file.write(content, StandardCharsets.UTF_8.name());
    }

    @Test
    void shortRefForClone_stripsRefsPrefix() {
        assertEquals("main", GitRepositoryFiles.shortRefForClone("refs/heads/main"));
        assertEquals("v1.2.3", GitRepositoryFiles.shortRefForClone("refs/tags/v1.2.3"));
        assertEquals("feature/x", GitRepositoryFiles.shortRefForClone("feature/x"));
        assertEquals("main", GitRepositoryFiles.shortRefForClone(" "));
        assertEquals("main", GitRepositoryFiles.shortRefForClone(null));
    }

    @Test
    void defaultGitReference_blankUsesManifestDefault() {
        assertEquals(
                RancherManifestBuilder.DEFAULT_REPOSITORY_REFERENCE,
                GitRepositoryFiles.defaultGitReference(null));
        assertEquals(
                RancherManifestBuilder.DEFAULT_REPOSITORY_REFERENCE,
                GitRepositoryFiles.defaultGitReference("  "));
        assertEquals("refs/heads/dev", GitRepositoryFiles.defaultGitReference(" refs/heads/dev "));
    }

    @Test
    void requireCloneContext_rejectsNullWorkspaceOrLauncher() {
        IOException ws = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireCloneContext(null, null, "Git file"));
        assertTrue(ws.getMessage().contains("Workspace is required"));
        assertTrue(ws.getMessage().contains("Git file"));

        FilePath workspace = new FilePath(tempDir.toFile());
        IOException launcher = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireCloneContext(workspace, null, "Helm values"));
        assertTrue(launcher.getMessage().contains("Launcher is required"));
        assertTrue(launcher.getMessage().contains("Helm values"));
    }

    @Test
    void normalizeRelativePath_stripsSlashAndRejectsDotDot() {
        assertEquals("apps/manifest.yaml", GitRepositoryFiles.normalizeRelativePath(" /apps/manifest.yaml "));
        assertEquals("values.yaml", GitRepositoryFiles.normalizeRelativePath("values.yaml"));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRelativePath(null));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRelativePath("  "));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRelativePath("../secret"));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRelativePath("///"));
        assertEquals("win/path.yaml", GitRepositoryFiles.normalizeRelativePath("win\\path.yaml"));
    }

    @Test
    void normalizeRepoUrl_requiresHttp() {
        assertEquals(
                "https://gitlab.example/group/repo.git",
                GitRepositoryFiles.normalizeRepoUrl(" https://gitlab.example/group/repo.git "));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRepoUrl("git@host:repo.git"));
        assertThrows(IllegalArgumentException.class, () -> GitRepositoryFiles.normalizeRepoUrl(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.normalizeRepoUrl("https://user:pass@gitlab.example/repo.git"));
    }

    @Test
    void deleteTempQuietly_removesDirectory() throws Exception {
        FilePath tmp = new FilePath(tempDir.resolve("to-delete").toFile());
        tmp.mkdirs();
        tmp.child("f.txt").write("x", StandardCharsets.UTF_8.name());
        assertTrue(tmp.exists());
        GitRepositoryFiles.deleteTempQuietly(tmp);
        assertFalse(tmp.exists());
    }

    @Test
    void writeAskpass_unixAndWindows() throws Exception {
        FilePath unixDir = new FilePath(tempDir.resolve("askpass-unix").toFile());
        unixDir.mkdirs();
        RancherCredentials.GitAuth auth = new RancherCredentials.GitAuth("user", "pass");
        FilePath sh = GitRepositoryFiles.writeAskpass(unixDir, auth, true);
        assertTrue(sh.getName().endsWith(".sh"));
        assertEquals("user", unixDir.child("askpass.username").readToString());
        assertEquals("pass", unixDir.child("askpass.password").readToString());

        FilePath winDir = new FilePath(tempDir.resolve("askpass-win").toFile());
        winDir.mkdirs();
        FilePath bat = GitRepositoryFiles.writeAskpass(
                winDir, new RancherCredentials.GitAuth(null, null), false);
        assertTrue(bat.getName().endsWith(".bat"));
        assertEquals("", winDir.child("askpass.username").readToString());
        assertTrue(bat.readToString().toLowerCase().contains("@echo off"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.writeAskpass(unixDir, null, true));
    }

    @Test
    void cloneCommandLine_andEnv() {
        assertEquals(
                List.of(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        "--branch",
                        "main",
                        "--",
                        "https://gitlab.example/r.git",
                        "."),
                GitRepositoryFiles.cloneCommandLine("https://gitlab.example/r.git", "refs/heads/main"));

        Map<String, String> withAuth = GitRepositoryFiles.cloneEnv("/tmp/askpass.sh");
        assertEquals("/tmp/askpass.sh", withAuth.get("GIT_ASKPASS"));
        assertEquals("0", withAuth.get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", withAuth.get("GCM_INTERACTIVE"));

        Map<String, String> publicClone = GitRepositoryFiles.cloneEnv(null);
        assertFalse(publicClone.containsKey("GIT_ASKPASS"));
        assertFalse(GitRepositoryFiles.cloneEnv("   ").containsKey("GIT_ASKPASS"));
    }

    @Test
    void askpassScriptContent_hasNoEmbeddedCredentials() {
        String unix = GitRepositoryFiles.askpassScriptContent(true);
        String win = GitRepositoryFiles.askpassScriptContent(false);
        assertTrue(unix.startsWith("#!/bin/sh"));
        assertTrue(unix.contains("askpass.username"));
        assertTrue(win.toLowerCase().contains("@echo off"));
        assertFalse(unix.contains("s3cret"));
        assertFalse(win.contains("s3cret"));
    }

    @Test
    void scrubSecrets_redactsUserinfoAndPassword() {
        RancherCredentials.GitAuth auth = new RancherCredentials.GitAuth("oauth2", "s3cret:token");
        String raw = "fatal: https://oauth2:s3cret:token@gitlab.example/r.git not found";
        String scrubbed = GitRepositoryFiles.scrubSecrets(raw, auth);
        assertFalse(scrubbed.contains("s3cret"));
        assertTrue(scrubbed.contains("***"));

        assertEquals("", GitRepositoryFiles.scrubSecrets(null, null));
        assertEquals("", GitRepositoryFiles.scrubSecrets("", null));
        assertEquals("plain", GitRepositoryFiles.scrubSecrets("plain", null));
        assertEquals(
                "no-secret",
                GitRepositoryFiles.scrubSecrets("no-secret", new RancherCredentials.GitAuth("user", "")));
        assertEquals(
                "still-plain",
                GitRepositoryFiles.scrubSecrets("still-plain", new RancherCredentials.GitAuth("user", null)));
    }

    @Test
    void readFile_testOverride_bypassesClone() throws Exception {
        GitRepositoryFiles.testOverride.set(req -> {
            assertEquals("https://gitlab.example/group/repo.git", req.repositoryUrl);
            assertEquals("refs/heads/main", req.reference);
            assertEquals("apps/values.yaml", req.relativePath);
            return "replicaCount: 2\n";
        });
        String content = GitRepositoryFiles.readFile(
                "https://gitlab.example/group/repo.git",
                "refs/heads/main",
                "apps/values.yaml",
                null);
        assertEquals("replicaCount: 2\n", content);
    }

    @Test
    void readFile_rejectsBlockedHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.readFile(
                        "http://169.254.169.254/repo.git",
                        "main",
                        "values.yaml",
                        cloneCtx(null, null, null, null)));
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    void readFile_cloneSuccess_returnsContent() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-ok").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(
                0, null, checkout -> seedFile(checkout, "values.yaml", "replicaCount: 2\n"));
        String content = GitRepositoryFiles.readFile(
                LOOPBACK_REPO,
                "refs/heads/main",
                "values.yaml",
                cloneCtx(null, workspace, launcher, TaskListener.NULL));
        assertEquals("replicaCount: 2\n", content);
    }

    @Test
    void readFile_cloneSuccess_withAskpass() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-auth").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(
                0, null, checkout -> seedFile(checkout, "nested/app.yaml", "kind: ConfigMap\n"), false);
        String content = GitRepositoryFiles.readFile(
                LOOPBACK_REPO,
                "main",
                "nested/app.yaml",
                cloneCtx(
                        new RancherCredentials.GitAuth("oauth2", "tok"),
                        workspace,
                        launcher,
                        TaskListener.NULL));
        assertEquals("kind: ConfigMap\n", content);
    }

    @Test
    void readFile_cloneSuccess_missingFile() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-missing").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(0, null, checkout -> {
        });
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "missing.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("File not found in repository"));
    }

    @Test
    void readFile_cloneSuccess_pathIsDirectory() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-dir").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(0, null, checkout -> checkout.child("values.yaml").mkdirs());
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("directory"));
    }

    @Test
    void readFile_cloneFailure_truncatesLongStderrAndScrubs() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-fail").toFile());
        workspace.mkdirs();
        String password = "s3cret:token";
        String longDetail = "fatal: https://oauth2:" + password + "@gitlab.example/r.git " + "x".repeat(300);
        RancherCredentials.GitAuth auth = new RancherCredentials.GitAuth("oauth2", password);
        Launcher launcher = new StubGitLauncher(128, longDetail, null);
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(auth, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("git exit 128"));
        assertTrue(ex.getMessage().contains("Detail:"));
        assertTrue(ex.getMessage().contains("…"));
        assertFalse(ex.getMessage().contains(password));
    }

    @Test
    void readFile_cloneFailure_blankStderrOmitsDetail() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-blank").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(1, "", null);
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("git exit 1"));
        assertFalse(ex.getMessage().contains("Detail:"));
    }

    @Test
    void readFile_requiresWorkspaceWhenNoOverride() {
        allowLoopback();
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(null, null, new StubGitLauncher(0, null, null), TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("Workspace is required"));
    }
}
