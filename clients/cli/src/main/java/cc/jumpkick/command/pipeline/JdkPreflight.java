// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ToolchainInstalls;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The build's JDK pre-flight: a pinned JDK that is not on disk is downloaded here, on the client,
 * before the request goes out and before any progress UI opens — with the bar, phases and done
 * line {@code jk jdk install} renders. The engine's own {@code ensure-jdk} step then finds it
 * installed and resolves without a download; it keeps its install path for clients that have no
 * terminal (HTTP, MCP).
 *
 * <p>Every spec comes from the engine's project summary, never from a manifest read here: the
 * summary's {@code jdk} is the same normalized resolver spec ({@code temurin-21} for
 * {@code jdk = "=temurin-21"}, or for {@code jdk = "temurin"} beside {@code java = 21}) and the
 * same effective {@code java} level the engine's {@code ensure-jdk} resolves with, so the two walks
 * cannot land on different downloads. The workspace root is asked once and stands for every member
 * that inherits its toolchain; a member is asked for its own summary only when its manifest names a
 * {@code jdk} or {@code java} of its own, or it carries a {@code .jdk-version} file.
 *
 * <p>{@link JdkEnsure#pendingInstall} decides first, so a JDK that is already on disk costs one
 * resolution walk and no output.
 */
public final class JdkPreflight {

    /**
     * One directory's answer: the spec and {@code java} level the engine resolves it with, its lock
     * pin, and the download that resolution lands on.
     */
    record Need(
            Path dir,
            @Nullable String jdkSpec,
            int javaRelease,
            Lockfile.@Nullable JdkPin lockJdk,
            JdkEnsure.Pending pending) {}

    private JdkPreflight() {}

    /**
     * Ensure every JDK the build entered at {@code dir} resolves to is installed. False when an
     * install was needed and failed — the failure has been rendered, so the build stops without
     * another line. {@code info} is the engine's summary of {@code dir}; with none, the engine's own
     * {@code ensure-jdk} step reports whatever is wrong with the project for real, and this stands
     * down rather than add a second, different error.
     */
    public static boolean ensure(
            Path dir, @Nullable ProjectInfo info, @Nullable Path jdksDir, BuildPlanConsole.Mode mode) {
        return ensure(dir, info, jdksDir, mode, ProjectInfos::orNull);
    }

    static boolean ensure(
            Path dir,
            @Nullable ProjectInfo info,
            @Nullable Path jdksDir,
            BuildPlanConsole.Mode mode,
            Function<Path, @Nullable ProjectInfo> summaries) {
        if (info == null) return true;
        for (Need need : needs(dir, info, jdksDir, summaries)) {
            if (!install(need, jdksDir, mode)) return false;
        }
        return true;
    }

    /**
     * The downloads a build entered at {@code dir} needs, in install order: the workspace root's,
     * the entry's, then each member that resolves on its own. Empty when everything is on disk.
     */
    static List<Need> needs(
            Path dir, ProjectInfo info, @Nullable Path jdksDir, Function<Path, @Nullable ProjectInfo> summaries) {
        Path entry = dir.toAbsolutePath().normalize();
        Path root = info.workspaceRootDir().isBlank()
                ? entry
                : Path.of(info.workspaceRootDir()).toAbsolutePath().normalize();
        ProjectInfo rootInfo = root.equals(entry) ? info : summaries.apply(root);
        List<Need> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        if (rootInfo != null) {
            seen.add(root);
            needOf(root, rootInfo, jdksDir).ifPresent(out::add);
        }
        if (seen.add(entry)) needOf(entry, info, jdksDir).ifPresent(out::add);
        List<String> members = rootInfo != null ? rootInfo.moduleDirs() : info.moduleDirs();
        for (String listed : members) {
            Path member = Path.of(listed).toAbsolutePath().normalize();
            if (!seen.add(member) || !resolvesOnItsOwn(member)) continue;
            ProjectInfo memberInfo = summaries.apply(member);
            if (memberInfo != null) needOf(member, memberInfo, jdksDir).ifPresent(out::add);
        }
        return out;
    }

    private static Optional<Need> needOf(Path dir, ProjectInfo info, @Nullable Path jdksDir) {
        String spec = info.jdk().isBlank() ? null : info.jdk();
        Lockfile.JdkPin lockJdk = lockJdkPin(dir);
        Optional<JdkEnsure.Pending> pending;
        try {
            pending = JdkEnsure.pendingInstall(dir, jdksDir, spec, info.javaRelease(), lockJdk);
        } catch (RuntimeException probeFailed) {
            // The engine's ensure-jdk step resolves (and reports) for real; the pre-flight only
            // ever adds the on-terminal install.
            return Optional.empty();
        }
        return pending.map(p -> new Need(dir, spec, info.javaRelease(), lockJdk, p));
    }

    private static boolean install(Need need, @Nullable Path jdksDir, BuildPlanConsole.Mode mode) {
        String header = header(need.dir(), need.javaRelease(), need.pending());
        return ToolchainInstalls.run(mode, header, null, (progress, warn) -> JdkEnsure.ensure(
                                need.dir(),
                                jdksDir,
                                need.jdkSpec(),
                                need.javaRelease(),
                                need.lockJdk(),
                                warn,
                                true,
                                progress)
                        .jdkOpt()
                        .orElseThrow(() -> new IOException(
                                "no JDK resolved for " + need.pending().spec())))
                .isPresent();
    }

    /** Why jk is installing, worded for the tier that asked: the file or switch that named the JDK. */
    static String header(Path dir, int javaRelease, JdkEnsure.Pending pending) {
        String spec = pending.spec();
        return switch (pending.tier()) {
            case SWITCH, JK_ENV -> "JDK " + spec + " is selected for this run (--jdk / JK_JDK) — installing it";
            case JDK_VERSION_FILE -> at(dir, ".jdk-version") + " pins JDK " + spec + " — installing it";
            case LOCKFILE -> {
                Path lock = LockPaths.lockFile(dir);
                yield at(lock.getParent(), String.valueOf(lock.getFileName())) + " pins JDK " + spec
                        + " — installing it";
            }
            case PROJECT_TOML -> at(dir, ManifestPaths.MANIFEST) + " pins JDK " + spec + " — installing it";
            case JAVA_RELEASE_FLOOR ->
                at(dir, ManifestPaths.MANIFEST) + " sets java = " + javaRelease + ", which needs a JDK " + spec
                        + " — installing one";
            case DEFAULT -> "no JDK is installed — installing " + spec;
            case JAVA_HOME, GRAALVM_HOME, PATH, NONE -> "JDK " + spec + " is not installed — installing it";
        };
    }

    /**
     * Whether a member's toolchain can differ from the workspace's: it names a {@code jdk} or a
     * {@code java} of its own, or carries a {@code .jdk-version} file. Any other member inherits
     * the root's answer, which has already been asked — so the summary round trip is spent only
     * where it can change the outcome. Presence only; every value comes from the engine.
     */
    private static boolean resolvesOnItsOwn(Path member) {
        if (Files.isRegularFile(member.resolve(".jdk-version"))) return true;
        Path manifest = member.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return false;
        TomlScan scan = TomlScan.scan(manifest, "jdk", "jdk-vendor", "jdk-version", "java");
        return scan.hasKey("jdk") || scan.hasKey("jdk-vendor") || scan.hasKey("jdk-version") || scan.hasKey("java");
    }

    /**
     * The lock's {@code [jdk]} pin, or null when there is no lock or it does not parse: a lock the
     * engine will refuse is the engine's error to report, structured and once, not this walk's.
     */
    private static Lockfile.@Nullable JdkPin lockJdkPin(Path dir) {
        Path lf = LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lf)) return null;
        try {
            return LockfileReader.read(lf).jdk();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String at(@Nullable Path dir, String file) {
        Path name = dir == null ? null : dir.getFileName();
        return (name == null ? String.valueOf(dir) : name.toString()) + "/" + file;
    }
}
