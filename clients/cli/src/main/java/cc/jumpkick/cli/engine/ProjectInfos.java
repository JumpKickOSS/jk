// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ProjectInfo;
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

    /** As {@link #orNull(Path)}; {@code counts=true} adds source/test tree counts. */
    public static @Nullable ProjectInfo orNull(Path dir, boolean counts) {
        String key = key(dir, null, null, counts);
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
        String key = key(dir, modules, affectedSince, false);
        ProjectInfo cached = MEMO.get(key);
        if (cached != null) return cached;
        ProjectInfo info;
        try {
            info = EngineClient.projectInfo(EnginePaths.current(), dir, modules, affectedSince);
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

    private static String key(Path dir, @Nullable String modules, @Nullable String affectedSince, boolean counts) {
        return dir.toAbsolutePath().normalize() + "\0" + (modules == null ? "" : modules) + "\0"
                + (affectedSince == null ? "" : affectedSince) + "\0" + counts;
    }
}
