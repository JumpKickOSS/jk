// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The variables of the shell running {@code jk} that ride every request whether or not a manifest
 * names them: the ones the engine reads to decide the request itself.
 *
 * <p>The engine is a daemon, so what it decides from the environment — that this is CI, which
 * terminal draws the output, where the JDKs and the Maven settings are, how git and the S3 and
 * forge repositories authenticate, the proxy — has to come from the shell that ran the command,
 * not the one that started it. These are those names, each with something in the engine that
 * reads it: the machine, the terminal, the toolchains, the CI run ({@link #CI_RUN}), git and its
 * ssh agent, the repository credentials ({@link #CREDENTIALS}) and every {@code JK_*} setting. A
 * prefix is forwarded whole only where the engine reads under it ({@link #PREFIX_READERS}); a CI
 * server's, a cloud SDK's or a tool's prefix also holds its secrets ({@code GITHUB_*} holds {@code
 * GITHUB_APP_PRIVATE_KEY}, {@code BUILDKITE*} the agent's token, {@code HOMEBREW_*} a GitHub token),
 * so those are named one by one or not at all. A variable outside them reaches the engine only when
 * something declares it: a module's {@code [env]} or {@code [test] env}, an {@code env:NAME} in a
 * plugin's table, a {@code ${NAME}} in {@code ~/.jk/config.toml} or a {@code ${env.NAME}} in {@code
 * settings.xml}.
 *
 * <p>Forwarding is not reaching: what the engine is sent stays in the engine. A forked worker — a
 * compiler, a plugin step, a test JVM — starts from its own allow-list ({@code WorkerEnv}) and the
 * names its module declares, and gets the whole forwarded shell only under {@code [env] inherit =
 * true}; the git JumpKick runs gets the {@code GIT_*} settings and {@code SSH_AUTH_SOCK}; a tool's
 * {@code --version} probe gets the forwarded shell without anything named like a credential. The
 * request's shell is never logged, journaled or answered.
 */
public final class RequestEnvNames {

    /** Forwarded by exact name: the machine, the terminal, the CI flag and front ends. */
    public static final List<String> NAMES = List.of(
            "CI",
            "HOME",
            "PATH",
            "SHELL",
            "USER",
            "USERNAME",
            "LOGNAME",
            "HOSTNAME",
            "TZ",
            "LANG",
            "LANGUAGE",
            "TMPDIR",
            "TMP",
            "TEMP",
            "BROWSER",
            "COLORTERM",
            "NO_COLOR",
            "FORCE_COLOR",
            "CLICOLOR",
            "CLICOLOR_FORCE",
            "NERD_FONT",
            "NODE_ENV",
            // the ssh agent git reaches an ssh remote through (a socket path), and the remote
            // login the terminal and keychain checks tell apart
            "SSH_AUTH_SOCK",
            "SSH_CONNECTION",
            "SSH_TTY",
            // where node version managers keep their installs (NodeDiscovery)
            "NVM_DIR",
            "FNM_DIR",
            "VOLTA_HOME",
            "HOMEBREW_CELLAR",
            // a coding agent's shell, which the agents a new project wires for are read from (AgentChoice)
            "CLAUDECODE",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH",
            "SystemRoot",
            "SystemDrive",
            "windir",
            "PATHEXT",
            "COMSPEC",
            "NUMBER_OF_PROCESSORS",
            "ProgramFiles",
            "ProgramFiles(x86)",
            "ProgramData");

    /**
     * The credentials the engine reads for itself, by exact name: a forge's token ({@code
     * ForgeAuth}: a forge-hosted Maven repository, a git dependency's https remote) and the AWS
     * chain ({@code AwsCredentialChain}, {@code S3Transport}: an {@code s3://} repository). The
     * engine files what it resolves with the redactor; none reaches a worker or a test JVM unless
     * the module declares the name or says {@code [env] inherit = true}, and none reaches a tool's
     * version probe. The git a template clone runs gets them, so a credential helper such as {@code
     * gh auth git-credential} can answer. {@code JK_REPO_*} and {@code JK_<FORGE>_TOKEN} ride under
     * {@code JK_} and are read the same way.
     */
    public static final List<String> CREDENTIALS = List.of(
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GITLAB_TOKEN",
            "BITBUCKET_TOKEN",
            "GITEA_TOKEN",
            "FORGEJO_TOKEN",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN",
            "AWS_PROFILE",
            "AWS_REGION",
            "AWS_DEFAULT_REGION",
            "AWS_ENDPOINT_URL");

    /** A prefix forwarded whole, and what in the engine reads under it. */
    public record Prefix(String prefix, String readBy) {}

    /**
     * Forwarded by prefix, each with its reader: jk's own settings, the locale, the terminal
     * emulators and multiplexers, the JDK and build-tool homes and version managers, git and the
     * credential manager its helper runs, the user directories and the display. None is a CI server's,
     * a cloud SDK's or a coding agent's.
     */
    public static final List<Prefix> PREFIX_READERS = List.of(
            new Prefix("JK_", "every JumpKick setting; JK_REPO_* and JK_<FORGE>_TOKEN by the credential resolvers"),
            new Prefix("LC_", "the locale a test JVM is given; LC_TERMINAL by the terminal checks"),
            new Prefix("TERM", "TerminalIdentity and the colour and hyperlink checks (TERM, TERM_PROGRAM)"),
            new Prefix("ITERM_", "TerminalIdentity: iTerm2's session"),
            new Prefix("KITTY_", "TerminalIdentity: kitty's window"),
            new Prefix("ALACRITTY_", "TerminalIdentity: Alacritty's window"),
            new Prefix("WEZTERM_", "TerminalIdentity: WezTerm's pane"),
            new Prefix("GHOSTTY_", "TerminalIdentity: Ghostty"),
            new Prefix("KONSOLE_", "TerminalIdentity: Konsole"),
            new Prefix("VTE_", "TerminalIdentity: VTE terminals"),
            new Prefix("TMUX", "TerminalIdentity: the tmux pane"),
            new Prefix("WT_", "TerminalIdentity: Windows Terminal's session"),
            new Prefix(
                    "VSCODE_",
                    "TerminalFonts (VS Code against VSCodium), and VS Code's askpass a template clone's git may run"),
            new Prefix("JAVA_", "JDK discovery (JAVA_HOME)"),
            new Prefix("JDK_", "JDK discovery (JDK_HOME)"),
            new Prefix("GRAALVM_", "GraalVM discovery (GRAALVM_HOME)"),
            new Prefix("KOTLIN_", "Kotlin discovery (KOTLIN_HOME)"),
            new Prefix("GRADLE_", "Gradle discovery and import (GRADLE_HOME, GRADLE_USER_HOME)"),
            new Prefix("MAVEN_", "Maven discovery and settings (MAVEN_HOME)"),
            new Prefix("M2_", "Maven discovery and settings (M2_HOME)"),
            new Prefix("ANDROID_", "the Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT)"),
            new Prefix("MISE_", "JDK discovery under mise (MISE_DATA_DIR)"),
            new Prefix("ASDF_", "JDK discovery under asdf (ASDF_DATA_DIR)"),
            new Prefix("JABBA_", "JDK discovery under jabba (JABBA_HOME)"),
            new Prefix("COURSIER_", "JDK discovery under Coursier (COURSIER_JVM_CACHE)"),
            new Prefix("GIT_", "the git JumpKick runs (GitCliExtension.passes): transport, auth and tracing"),
            new Prefix("GCM_", "Git Credential Manager, run as the credential helper of a template clone's git"),
            new Prefix("XDG_", "the config, data and runtime directories (XDG_CONFIG_HOME, XDG_RUNTIME_DIR)"),
            new Prefix("DISPLAY", "the display a suite opens (BuildEnv.DISPLAY)"),
            new Prefix("WAYLAND_", "the display a suite opens (BuildEnv.DISPLAY)"),
            new Prefix("XAUTHORITY", "the display a suite opens (BuildEnv.DISPLAY)"));

    /** {@link #PREFIX_READERS}' prefixes. */
    public static final List<String> PREFIXES =
            PREFIX_READERS.stream().map(Prefix::prefix).toList();

    /**
     * The CI run a build came from, by exact name and never by prefix: what the run's record names
     * as its CI provider, run id, run page, event, branch and repository. A CI server's prefix also
     * holds its secrets (GitLab's {@code CI_JOB_TOKEN}, {@code CI_REGISTRY_PASSWORD}, CircleCI's
     * tokens), so only these reach the engine; none names a credential.
     */
    public static final List<String> CI_RUN = List.of(
            "GITHUB_ACTIONS",
            "GITHUB_RUN_ID",
            "GITHUB_SERVER_URL",
            "GITHUB_REPOSITORY",
            "GITHUB_EVENT_NAME",
            "GITHUB_HEAD_REF",
            "GITHUB_REF_NAME",
            "GITLAB_CI",
            "CI_PIPELINE_ID",
            "CI_PIPELINE_URL",
            "CI_PIPELINE_SOURCE",
            "CI_MERGE_REQUEST_SOURCE_BRANCH_NAME",
            "CI_COMMIT_REF_NAME",
            "CI_PROJECT_URL",
            "BUILDKITE",
            "BUILDKITE_BUILD_ID",
            "BUILDKITE_BUILD_URL",
            "BUILDKITE_SOURCE",
            "BUILDKITE_BRANCH",
            "CIRCLECI",
            "CIRCLE_WORKFLOW_ID",
            "CIRCLE_BUILD_NUM",
            "CIRCLE_BUILD_URL",
            "CIRCLE_BRANCH");

    private static final Set<String> NAMES_SET = Set.copyOf(
            Stream.of(NAMES, CREDENTIALS, CI_RUN).flatMap(List::stream).toList());
    private static final Set<String> NAMES_UPPER = Set.copyOf(upper(NAMES_SET.stream()));
    private static final List<String> PREFIXES_UPPER =
            PREFIXES.stream().map(p -> p.toUpperCase(Locale.ROOT)).toList();

    private RequestEnvNames() {}

    /**
     * Whether the engine reads {@code name} for itself: one of {@link #NAMES}, {@link #CREDENTIALS}
     * or {@link #CI_RUN}, under one of {@link #PREFIXES}, the proxy, display, container, JDK-root and front-end variables
     * {@link BuildEnv} forwards, or a framework's public prefix. {@code caseInsensitive} on Windows,
     * whose variable names are.
     */
    public static boolean engineReads(String name, boolean caseInsensitive) {
        if (name.isEmpty()) return false;
        String key = caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
        if ((caseInsensitive ? NAMES_UPPER : NAMES_SET).contains(key)) return true;
        for (String prefix : caseInsensitive ? PREFIXES_UPPER : PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        for (String prefix : BuildEnv.NODE_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return caseInsensitive ? FORWARDED_UPPER.contains(key) : FORWARDED.contains(name);
    }

    /** The names {@link BuildEnv} forwards by exact name: the request's shell, the machine and the JDK root. */
    private static final Set<String> FORWARDED =
            Set.copyOf(Stream.of(BuildEnv.SHELL, BuildEnv.MACHINE, BuildEnv.JDK_ROOT)
                    .flatMap(List::stream)
                    .toList());

    private static final Set<String> FORWARDED_UPPER = Set.copyOf(upper(FORWARDED.stream()));

    private static Set<String> upper(Stream<String> names) {
        return names.map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
