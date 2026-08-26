package io.jenkins.plugins.ranchermanager;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Shallow Git clone on the agent to read a single file (manifest or Helm values).
 * Never logs credentials, askpass contents, clone URLs, or file bodies.
 */
final class GitRepositoryFiles {

    static final AtomicReference<Function<FetchRequest, String>> testOverride = new AtomicReference<>();

    private static final String ASKPASS_USERNAME_FILE = "askpass.username";
    private static final String ASKPASS_PASSWORD_FILE = "askpass.password";
    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private GitRepositoryFiles() {
    }

    static final class FetchRequest {
        final String repositoryUrl;
        final String reference;
        final String relativePath;

        FetchRequest(String repositoryUrl, String reference, String relativePath) {
            this.repositoryUrl = repositoryUrl;
            this.reference = reference;
            this.relativePath = relativePath;
        }
    }

    static final class CloneContext {
        final RancherCredentials.GitAuth auth;
        final FilePath workspace;
        final Launcher launcher;
        final TaskListener listener;

        CloneContext(
                RancherCredentials.GitAuth auth,
                FilePath workspace,
                Launcher launcher,
                TaskListener listener) {
            this.auth = auth;
            this.workspace = workspace;
            this.launcher = launcher;
            this.listener = listener;
        }
    }

    static String readFile(
            String repositoryUrl, String reference, String relativePath, CloneContext ctx)
            throws IOException, InterruptedException {
        String repo = normalizeRepoUrl(repositoryUrl);
        String path = normalizeRelativePath(relativePath);
        String ref = defaultGitReference(reference);

        Function<FetchRequest, String> override = testOverride.get();
        if (override != null) {
            return override.apply(new FetchRequest(repo, ref, path));
        }
        ConnectionTester.assertHostAllowed(repo, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
        requireCloneContext(ctx.workspace, ctx.launcher, "Git file");

        FilePath tmp = ctx.workspace.createTempDir("rancher-git-fetch", null);
        try {
            FilePath checkout = shallowClone(repo, ref, ctx.auth, tmp, ctx.launcher, ctx.listener);
            FilePath file = checkout.child(path);
            if (!file.exists()) {
                throw new IOException("File not found in repository: " + path);
            }
            if (file.isDirectory()) {
                throw new IOException("Path is a directory, not a file: " + path);
            }
            return file.readToString();
        } finally {
            deleteTempQuietly(tmp);
        }
    }

    static String defaultGitReference(String reference) {
        return reference == null || reference.isBlank()
                ? RancherManifestBuilder.DEFAULT_REPOSITORY_REFERENCE
                : reference.trim();
    }

    static void requireCloneContext(FilePath workspace, Launcher launcher, String label)
            throws IOException {
        if (workspace == null) {
            throw new IOException("Workspace is required to fetch " + label + " from Git.");
        }
        if (launcher == null) {
            throw new IOException("Launcher is required to fetch " + label + " from Git.");
        }
    }

    static String normalizeRepoUrl(String url) {
        RancherManifestBuilder.requireHttpRepoUrl(url);
        return url.trim();
    }

    static String normalizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("File path is required.");
        }
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isBlank() || p.contains("..")) {
            throw new IllegalArgumentException("File path must be relative and must not contain '..'.");
        }
        return p;
    }

    static void deleteTempQuietly(FilePath tmp) {
        try {
            tmp.deleteRecursive();
        } catch (InterruptedException cleanup) {
            Thread.currentThread().interrupt();
        } catch (IOException cleanup) {
            // best-effort
        }
    }

    private static FilePath shallowClone(
            String repo,
            String ref,
            RancherCredentials.GitAuth auth,
            FilePath tmp,
            Launcher launcher,
            TaskListener listener) throws IOException, InterruptedException {
        String askpassRemote = null;
        if (auth != null) {
            askpassRemote = writeAskpass(tmp, auth, launcher.isUnix()).getRemote();
        }
        FilePath checkout = tmp.child("repo");
        checkout.mkdirs();
        ArgumentListBuilder args = new ArgumentListBuilder();
        for (String part : cloneCommandLine(repo, ref)) {
            args.add(part);
        }
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = launcher.launch()
                .cmds(args)
                .envs(cloneEnv(askpassRemote))
                .pwd(checkout)
                .stdout(OutputStream.nullOutputStream())
                .stderr(err)
                .quiet(true)
                .start()
                .joinWithTimeout(10, TimeUnit.MINUTES, listener);
        if (code != 0) {
            String detail = scrubSecrets(err.toString(StandardCharsets.UTF_8), auth);
            if (detail.length() > 240) {
                detail = detail.substring(0, 240) + "…";
            }
            throw new IOException(
                    "Failed to clone Git repository (git exit " + code
                            + "). Check URL, reference, credentials, and that git is on PATH."
                            + (detail.isBlank() ? "" : " Detail: " + detail.replaceAll("\\s+", " ").trim()));
        }
        return checkout;
    }

    static List<String> cloneCommandLine(String repositoryUrl, String reference) {
        String checkout = shortRefForClone(reference);
        List<String> cmd = new ArrayList<>(9);
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--depth");
        cmd.add("1");
        cmd.add("--branch");
        cmd.add(checkout);
        cmd.add("--");
        cmd.add(repositoryUrl);
        cmd.add(".");
        return cmd;
    }

    static Map<String, String> cloneEnv(String askpassRemote) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        if (askpassRemote != null && !askpassRemote.isBlank()) {
            env.put("GIT_ASKPASS", askpassRemote);
        }
        return env;
    }

    static String askpassScriptContent(boolean unix) {
        if (unix) {
            return "#!/bin/sh\n"
                    + "DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                    + "case \"$1\" in\n"
                    + "*[Uu]sername*)\n"
                    + "  cat \"$DIR/" + ASKPASS_USERNAME_FILE + "\"\n"
                    + "  ;;\n"
                    + "*)\n"
                    + "  cat \"$DIR/" + ASKPASS_PASSWORD_FILE + "\"\n"
                    + "  ;;\n"
                    + "esac\n";
        }
        return "@echo off\r\n"
                + "echo.%*| find /I \"Username\" >NUL\r\n"
                + "if errorlevel 1 (\r\n"
                + "  type \"%~dp0" + ASKPASS_PASSWORD_FILE + "\"\r\n"
                + ") else (\r\n"
                + "  type \"%~dp0" + ASKPASS_USERNAME_FILE + "\"\r\n"
                + ")\r\n";
    }

    static FilePath writeAskpass(FilePath tmp, RancherCredentials.GitAuth auth, boolean unix)
            throws IOException, InterruptedException {
        if (auth == null) {
            throw new IllegalArgumentException("GitAuth is required to write askpass");
        }
        tmp.child(ASKPASS_USERNAME_FILE).write(auth.username == null ? "" : auth.username, UTF_8);
        tmp.child(ASKPASS_PASSWORD_FILE).write(auth.password == null ? "" : auth.password, UTF_8);
        FilePath askpass = tmp.child(unix ? "askpass.sh" : "askpass.bat");
        askpass.write(askpassScriptContent(unix), UTF_8);
        if (unix) {
            askpass.chmod(0755);
        }
        return askpass;
    }

    static String scrubSecrets(String text, RancherCredentials.GitAuth auth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1***@");
        if (auth != null && auth.password != null && !auth.password.isEmpty()) {
            out = out.replace(auth.password, "***");
        }
        return out;
    }

    static String shortRefForClone(String reference) {
        if (reference == null || reference.isBlank()) {
            return "main";
        }
        String ref = reference.trim();
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }
}
