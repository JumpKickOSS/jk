// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What this process can tell about its session from its environment and a few file checks, without
 * starting anything: whether it runs on a CI server or in a container, where no keychain is
 * expected, whether it is a remote login with no display, where nobody could unlock one, and
 * whether a D-Bus session bus is there to ask.
 *
 * <p>Headless is the normal case for a build tool: a CI server, a build container, an SSH session
 * and a systemd service have no desktop. Every answer here is cheap and silent.
 */
public final class HostSession {

    /** The variables CI services set, beyond {@code CI} itself. */
    private static final List<String> CI_MARKERS = List.of(
            "GITHUB_ACTIONS",
            "GITLAB_CI",
            "JENKINS_URL",
            "BUILDKITE",
            "CIRCLECI",
            "TF_BUILD",
            "TEAMCITY_VERSION",
            "BITBUCKET_BUILD_NUMBER",
            "CODEBUILD_BUILD_ID",
            "DRONE",
            "WOODPECKER_CI");

    /** Files a container runtime leaves at the root of the container's filesystem. */
    private static final List<Path> CONTAINER_MARKERS = List.of(Path.of("/.dockerenv"), Path.of("/run/.containerenv"));

    private final Function<String, @Nullable String> env;
    private final Predicate<Path> exists;

    public HostSession(Function<String, @Nullable String> env, Predicate<Path> exists) {
        this.env = Objects.requireNonNull(env, "env");
        this.exists = Objects.requireNonNull(exists, "exists");
    }

    /** This process's session. */
    public static HostSession current() {
        return new HostSession(JkDirs::env, Files::exists);
    }

    /** A desktop session with no CI or container marker, no D-Bus and nothing on {@code PATH}: tests. */
    public static HostSession desktop() {
        return new HostSession(name -> null, path -> false);
    }

    /**
     * Why no keychain is expected here, or {@code null} when this may be a desktop: {@code "a CI
     * server (GITHUB_ACTIONS is set)"}, {@code "a container (/.dockerenv exists)"}.
     */
    public @Nullable String headless() {
        if (EnvValues.isCi(env)) return "a CI server (CI is set)";
        for (String marker : CI_MARKERS) {
            if (set(marker)) return "a CI server (" + marker + " is set)";
        }
        if (set("KUBERNETES_SERVICE_HOST")) return "a container (KUBERNETES_SERVICE_HOST is set)";
        if (set("container")) return "a container (container is set)";
        for (Path marker : CONTAINER_MARKERS) {
            if (exists.test(marker)) return "a container (" + marker + " exists)";
        }
        return null;
    }

    /**
     * Whether this is an SSH login with no display: a keychain found here is locked, and nobody at
     * this terminal can unlock it, so its refusal is expected rather than news.
     */
    public boolean remote() {
        boolean ssh = set("SSH_CONNECTION") || set("SSH_TTY");
        return ssh && !set("DISPLAY") && !set("WAYLAND_DISPLAY");
    }

    /**
     * Whether a D-Bus session bus is there to ask: {@code DBUS_SESSION_BUS_ADDRESS}, or the
     * per-user socket under {@code XDG_RUNTIME_DIR}. A bus that only an X11 autolaunch would start
     * does not count: jk never starts a desktop daemon.
     */
    public boolean sessionBus() {
        if (set("DBUS_SESSION_BUS_ADDRESS")) return true;
        String runtime = env.apply("XDG_RUNTIME_DIR");
        return runtime != null && !runtime.isBlank() && exists.test(Path.of(runtime, "bus"));
    }

    /** Whether {@code tool} is in a directory on {@code PATH}. */
    public boolean onPath(String tool) {
        for (String entry : SearchPath.entries(env.apply("PATH"))) {
            Path dir = SearchPath.path(entry);
            if (dir != null && exists.test(dir.resolve(tool))) return true;
        }
        return false;
    }

    private boolean set(String name) {
        String value = env.apply(name);
        return value != null && !value.isBlank();
    }
}
