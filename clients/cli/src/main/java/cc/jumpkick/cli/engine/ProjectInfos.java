// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Per-invocation memo over the engine's {@code project-info} verb: one CLI run issues the same
 * summary up to N times (peek, selection, labels, per-module release probes) and each engine call
 * re-parses the workspace. The CLI process is one-shot, so only in-run staleness matters —
 * {@link #forget} is called after anything that mutates lock/manifest state mid-run (a lock
 * refresh, an engine-hosted {@code jk.toml} edit).
 */
public final class ProjectInfos {

    private static final ConcurrentHashMap<String, ProjectInfo> MEMO = new ConcurrentHashMap<>();

    private ProjectInfos() {}

    /** Engine project summary, or {@code null} when unavailable / errored. */
    public static @Nullable ProjectInfo orNull(Path dir) {
        return orNull(dir, false);
    }

    /**
     * Why the engine declines to describe {@code dir} — a shadow it does not render, a manifest it
     * cannot parse — as the one line the engine reported; {@code null} when it describes the
     * project (memoised for the calls that follow) or when no engine answered.
     */
    public static @Nullable String refusal(Path dir) {
        String key = key(dir, null, null, false, false);
        if (MEMO.containsKey(key)) return null;
        try {
            ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), dir, null, null, false);
            if (info.error() != null) return info.error();
            MEMO.put(key, info);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** As {@link #orNull(Path)}; {@code counts=true} adds source/test tree counts. */
    public static @Nullable ProjectInfo orNull(Path dir, boolean counts) {
        String key = key(dir, null, null, false, counts);
        ProjectInfo cached = MEMO.get(key);
        if (cached != null) return cached;
        try {
            ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), dir, null, null, counts);
            if (info.error() != null) return null;
            MEMO.put(key, info);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** Module-selection summary; failures come back as {@link ProjectInfo#error()}, never null. */
    public static ProjectInfo orError(Path dir, @Nullable String modules, @Nullable String affectedSince) {
        return orError(dir, modules, affectedSince, false);
    }

    public static ProjectInfo orError(
            Path dir, @Nullable String modules, @Nullable String affectedSince, boolean affectedWip) {
        String key = key(dir, modules, affectedSince, affectedWip, false);
        ProjectInfo cached = MEMO.get(key);
        if (cached != null) return cached;
        ProjectInfo info;
        try {
            info = EngineClient.projectInfo(EnginePaths.current(), dir, modules, affectedSince, affectedWip, false);
        } catch (Exception e) {
            return ProjectInfo.error(String.valueOf(e.getMessage()));
        }
        if (info.error() == null || info.error().isBlank()) MEMO.put(key, info);
        return info;
    }

    /** Drop memoized summaries — call after a lock refresh or any manifest edit mid-run. */
    public static void forget() {
        MEMO.clear();
    }

    private static String key(
            Path dir, @Nullable String modules, @Nullable String affectedSince, boolean affectedWip, boolean counts) {
        return dir.toAbsolutePath().normalize() + "\0" + (modules == null ? "" : modules) + "\0"
                + (affectedSince == null ? "" : affectedSince) + "\0" + affectedWip + "\0" + counts;
    }

    /** The coordinate the engine knows {@code dir} by, else its directory name — the build's display target. */
    public static String buildTarget(Path buildFile, Path dir) {
        var info = orNull(dir);
        if (info != null) return info.coord();
        return dir.getFileName() == null ? "" : dir.getFileName().toString();
    }
}
