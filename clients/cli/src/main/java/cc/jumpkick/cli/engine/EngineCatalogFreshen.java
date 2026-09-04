// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The three engine-start policies for refreshing a network-backed catalog. {@link #freshenCatalog}
 * starts an engine if none is running and never throws; {@link #freshenCatalogNow} starts one and
 * returns the engine's error; {@link #freshenCatalogIfRunning} never starts one, because the
 * bare-machine JDK bootstrap may have no JDK to host an engine yet.
 */
public final class EngineCatalogFreshen {

    private EngineCatalogFreshen() {}

    /**
     * On-demand, engine-hosted freshen of a network-backed catalog — {@code "templates"} (before
     * {@code jk new}/{@code init}) or {@code "libraries"} (before {@code jk lock}/{@code update}).
     * Starts the engine if it isn't already running (these two commands have no bootstrap concern —
     * they never need to run before a JDK exists). {@code url}/{@code cacheFile} override the
     * default source/destination ({@code "libraries"} only; {@code null} for {@code "templates"}).
     * Best-effort: never throws — a stale/offline catalog is not this call's problem, the caller
     * resolves against whatever the local cache already holds.
     *
     * <p>{@code jk jdk install}/{@code update} must not use this — use {@link
     * #freshenCatalogIfRunning} instead, which never starts an engine.
     */
    public static void freshenCatalog(
            EnginePaths.Paths paths, String catalog, boolean offline, String url, Path cacheFile) {
        if (offline) return; // nothing to freshen without a network
        try {
            EngineSpawn.ensure(paths, Jk.VERSION);
        } catch (IOException e) {
            return; // no engine to host the freshen — local resolution proceeds against the cache
        }
        EngineReads.freshenCatalog(paths, catalog, false, url, cacheFile == null ? null : cacheFile.toString(), false);
    }

    /**
     * As {@link #freshenCatalog} but always hits the network and returns the engine error (or
     * {@code null} on success). Used by {@code jk library update}.
     */
    public static String freshenCatalogNow(EnginePaths.Paths paths, String catalog, String url, Path cacheFile)
            throws IOException {
        EngineSpawn.ensure(paths, Jk.VERSION);
        return EngineReads.freshenCatalogNow(paths, catalog, url, cacheFile == null ? null : cacheFile.toString());
    }

    /**
     * As {@link #freshenCatalog}, but for {@code "jdks"} from {@code jk jdk install}/{@code
     * update} specifically: it must work to bootstrap a bare machine that has no JDK at all yet
     * (possibly the one that will host the engine), so it never starts an engine — only an
     * already-reachable one is asked to freshen. Returns {@code true} when it delegated (an engine
     * answered); {@code false} means nothing happened here and the caller must fetch {@code
     * jdks.json} itself ({@code JdkCatalogClient}). Never throws.
     *
     * <p>Once a healthy engine is running, every client — this CLI path included — funnels JDK
     * installs through it the same way the web dashboard and MCP always do, so there is one place
     * that actually touches the JDK feed's network when the engine is available.
     */
    public static boolean freshenCatalogIfRunning(EnginePaths.Paths paths, String catalog, String url, Path cacheFile) {
        if (!EngineProbe.reachable(EnginePaths.activeSocket(paths))) return false;
        EngineReads.freshenCatalog(paths, catalog, false, url, cacheFile == null ? null : cacheFile.toString());
        return true;
    }
}
