// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ToolchainInstalls;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
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
import org.jspecify.annotations.Nullable;

/**
 * The build's JDK pre-flight: a pinned JDK that is not on disk is downloaded here, on the client,
 * before the request goes out and before any progress UI opens — with the bar, phases and done
 * line {@code jk jdk install} renders. The engine's own {@code ensure-jdk} step then finds it
 * installed and resolves without a download; it keeps its install path for clients that have no
 * terminal (HTTP, MCP).
 *
 * <p>Asks {@link JdkEnsure#pendingInstall} first, so a JDK that is already on disk costs one
 * resolution walk and no output. The entry project's answer comes from the engine's summary (the
 * inherited pin and the {@code java} floor); a workspace member that names its own {@code jdk}
 * is read from its manifest, since the summary carries only the root's.
 */
final class JdkPreflight {

    private JdkPreflight() {}

    /**
     * Ensure every JDK the build at {@code dir} resolves to is installed. False when an install was
     * needed and failed — the failure has been rendered, so the build stops without another line.
     */
    static boolean ensure(Path dir, @Nullable ProjectInfo info, @Nullable Path jdksDir, BuildPlanConsole.Mode mode) {
        Path root = dir.toAbsolutePath().normalize();
        if (!ensureOne(root, jdksDir, info == null ? null : info.jdk(), info == null ? 0 : info.javaRelease(), mode)) {
            return false;
        }
        for (Path member : membersWithOwnPin(root)) {
            String spec = ownPin(member);
            if (spec != null && !ensureOne(member, jdksDir, spec, 0, mode)) return false;
        }
        return true;
    }

    private static boolean ensureOne(
            Path dir, @Nullable Path jdksDir, @Nullable String jdkSpec, int javaRelease, BuildPlanConsole.Mode mode) {
        Lockfile.JdkPin lockJdk = lockJdkPin(dir);
        Optional<String> pending;
        try {
            pending = JdkEnsure.pendingInstall(dir, jdksDir, jdkSpec, javaRelease, lockJdk);
        } catch (RuntimeException probeFailed) {
            // The engine's ensure-jdk step resolves (and reports) for real; the pre-flight only
            // ever adds the on-terminal install.
            return true;
        }
        if (pending.isEmpty()) return true;
        String spec = pending.get();
        String header = manifestName(dir) + " pins JDK " + spec + " — installing it";
        return ToolchainInstalls.run(mode, header, null, progress -> JdkEnsure.ensure(
                                dir, jdksDir, jdkSpec, javaRelease, lockJdk, m -> {}, true, progress)
                        .jdkOpt()
                        .orElseThrow(() -> new IOException("no JDK resolved for " + spec)))
                .isPresent();
    }

    /** Workspace members whose own manifest names a {@code jdk} (not the inherited one). */
    private static List<Path> membersWithOwnPin(Path root) {
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        List<String> rels = TomlScan.scan(manifest, "workspace.modules").stringArray("workspace.modules");
        if (rels.isEmpty()) return List.of();
        Set<Path> dirs = new LinkedHashSet<>();
        for (String rel : WorkspaceModules.expand(root, rels)) {
            Path member = root.resolve(rel).normalize();
            if (!member.equals(root) && Files.isRegularFile(member.resolve(ManifestPaths.MANIFEST))) dirs.add(member);
        }
        return new ArrayList<>(dirs);
    }

    /** The member's own {@code jdk = "…"}, or null when it inherits the workspace's. */
    private static @Nullable String ownPin(Path member) {
        String spec =
                TomlScan.scan(member.resolve(ManifestPaths.MANIFEST), "jdk").get("jdk");
        if (spec == null || spec.isBlank() || "workspace".equalsIgnoreCase(spec.trim())) return null;
        return spec.trim();
    }

    private static Lockfile.@Nullable JdkPin lockJdkPin(Path dir) {
        Path lf = LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lf)) return null;
        try {
            return LockfileReader.read(lf).jdk();
        } catch (IOException e) {
            return null;
        }
    }

    private static String manifestName(Path dir) {
        Path name = dir.getFileName();
        return (name == null ? dir.toString() : name.toString()) + "/" + ManifestPaths.MANIFEST;
    }
}
