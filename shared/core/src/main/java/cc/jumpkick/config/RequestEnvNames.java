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
 * terminal draws the output, where the JDKs and the Maven settings are, how git and the cloud
 * SDKs authenticate, the proxy — has to come from the shell that ran the command, not the one that
 * started it. These are those names: the machine, the terminal, the toolchains, the CI servers,
 * git, ssh and the cloud credentials chains, and every {@code JK_*} setting. A variable outside
 * them reaches the engine only when something declares it: a module's {@code [env]} or {@code
 * [test] env}, an {@code env:NAME} in a plugin's table, a {@code ${NAME}} in {@code
 * ~/.jk/config.toml} or a {@code ${env.NAME}} in {@code settings.xml}. Forwarding is not reaching:
 * a forked worker still starts from its own allow-list ({@code WorkerEnv}).
 */
public final class RequestEnvNames {

    /** Forwarded by exact name. */
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
            "GH_TOKEN",
            "GITEA_TOKEN",
            "FORGEJO_TOKEN",
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
            "ProgramData");

    /**
     * Forwarded by prefix: jk's own settings, the terminal emulators and multiplexers, the
     * toolchains and version managers, git, ssh, gpg and the desktop session, the cloud SDKs, the
     * container runtime, the CI servers and the coding agents a run can tell apart.
     */
    public static final List<String> PREFIXES = List.of(
            "JK_",
            "LC_",
            "TERM",
            "ITERM_",
            "KITTY_",
            "ALACRITTY_",
            "WEZTERM_",
            "GHOSTTY_",
            "KONSOLE_",
            "VTE_",
            "TMUX",
            "WT_",
            "VSCODE_",
            "JAVA_",
            "JDK_",
            "GRAALVM_",
            "KOTLIN_",
            "GRADLE_",
            "MAVEN_",
            "M2_",
            "ANDROID_",
            "SDKMAN_",
            "MISE_",
            "ASDF_",
            "JABBA_",
            "COURSIER_",
            "HOMEBREW_",
            "NVM_",
            "FNM_",
            "VOLTA_",
            "GIT_",
            "GCM_",
            "SSH_",
            "GPG_",
            "DBUS_",
            "XDG_",
            "DISPLAY",
            "WAYLAND_",
            "XAUTHORITY",
            "AWS_",
            "CLOUDSDK_",
            "GOOGLE_",
            "AZURE_",
            "DOCKER_",
            "TESTCONTAINERS_",
            "KUBERNETES_",
            "GITHUB_",
            "GITLAB_",
            "BITBUCKET_",
            "CODEBUILD_",
            "JENKINS_",
            "TEAMCITY_",
            "TF_BUILD",
            "WOODPECKER_",
            "BUILDKITE",
            "CIRCLECI",
            "TRAVIS",
            "DRONE",
            "CLAUDE",
            "CODEX_");

    private static final Set<String> NAMES_SET = Set.copyOf(NAMES);
    private static final Set<String> NAMES_UPPER = upper(NAMES.stream());
    private static final List<String> PREFIXES_UPPER =
            PREFIXES.stream().map(p -> p.toUpperCase(Locale.ROOT)).toList();

    private RequestEnvNames() {}

    /**
     * Whether the engine reads {@code name} for itself: one of {@link #NAMES} or under one of
     * {@link #PREFIXES}, the proxy, display, container, JDK-root and front-end variables
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
        return BuildEnv.SHELL.contains(name) || BuildEnv.MACHINE.contains(name) || BuildEnv.JDK_ROOT.contains(name);
    }

    private static Set<String> upper(Stream<String> names) {
        return names.map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
