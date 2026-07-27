// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolInstaller;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.http.Http;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Resolves the Kotlin distribution for {@code kotlinc}: {@code KOTLIN_HOME}, else auto-install
 * under {@code $JK_CACHE_DIR/tools/kotlin/}.
 */
public final class CompileToolchain {

    private CompileToolchain() {}

    /**
     * Resolve a Kotlin installation, auto-downloading via {@link ToolInstaller} if neither {@code
     * KOTLIN_HOME} nor {@code $JK_CACHE_DIR/tools/kotlin/} is populated.
     *
     * @param cacheDir the {@link Cas} root (typically {@link JkDirs#cache()})
     */
    public static Path resolveKotlinHome(Path cacheDir) {
        return resolveKotlinHome(cacheDir, null, NO_NOTICE);
    }

    /** Notice sink that drops provisioning messages (no-op). */
    private static final Consumer<String> NO_NOTICE = s -> {};

    /**
     * Pick the Kotlin compiler version to provision: the version pinned in {@code jk.lock} (resolved
     * by {@code jk lock}) if present, else an exact {@code project.kotlin} pin, else {@code null} —
     * which falls back to the bundled default distribution.
     */
    public static String kotlinVersionFor(cc.jumpkick.lock.Lockfile lock, JkBuild project) {
        if (lock != null && lock.kotlin() != null && !lock.kotlin().isBlank()) {
            return lock.kotlin();
        }
        if (project != null && project.project().kotlin() instanceof cc.jumpkick.model.VersionSelector.Exact exact) {
            return exact.version();
        }
        return null;
    }

    /**
     * Pick the Groovy compiler version to provision, mirroring {@link #kotlinVersionFor}:
     * the locked {@code org.apache.groovy:groovy} runtime first — the compiler must match what
     * actually ships (caret/tilde pins and BOM-managed grails floats resolve here, JK-1223) —
     * else an exact {@code project.groovy} pin, else {@code null} (bundled default).
     */
    public static String groovyVersionFor(cc.jumpkick.lock.Lockfile lock, JkBuild project) {
        if (lock != null) {
            for (cc.jumpkick.lock.Lockfile.Artifact a : lock.artifacts()) {
                String name = a.name();
                if (name.equals("org.apache.groovy:groovy") || name.startsWith("org.apache.groovy:groovy:")) {
                    return a.version();
                }
            }
        }
        if (project != null && project.project().groovy() instanceof cc.jumpkick.model.VersionSelector.Exact exact) {
            return exact.version();
        }
        return null;
    }

    /**
     * Resolve a Kotlin installation pinned to a specific version (e.g. from a script's {@code
     * //KOTLIN 2.1.0} directive). Passes {@code null} to fall back to the bundled default
     * distribution.
     */
    public static Path resolveKotlinHome(Path cacheDir, String versionOverride) {
        return resolveKotlinHome(cacheDir, versionOverride, NO_NOTICE);
    }

    /**
     * As {@link #resolveKotlinHome(Path, String)}, but reports a one-line provisioning notice
     * ("Linked/Installed Kotlin …") to {@code notice} instead of a stream — the caller (the CLI view,
     * or a step's {@code StepContext::output}) decides how to surface it.
     */
    public static Path resolveKotlinHome(Path cacheDir, String versionOverride, Consumer<String> notice) {
        // ToolProvisioning already runs the EnvVarProbe (which reads
        // KOTLIN_HOME), so we don't need a separate fast-path. Going
        // through the full pipeline guarantees we leave a symlink under
        // $JK_CACHE_DIR/tools/kotlin/<version>/ — subsequent invocations
        // don't depend on the env var still being set.
        Path toolsRoot = JkDirs.cache().resolve("tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);
        ToolDistribution dist;
        if (versionOverride == null || versionOverride.isBlank()) {
            dist = KotlinResolver.defaultDistribution();
        } else {
            URI uri = URI.create("https://github.com/JetBrains/kotlin/releases/download/v"
                    + versionOverride
                    + "/kotlin-compiler-"
                    + versionOverride
                    + ".zip");
            dist = new ToolDistribution(BuildTool.KOTLIN, versionOverride, uri, "zip");
        }
        try {
            boolean refresh =
                    cc.jumpkick.config.SessionContext.current().config().forceOr(false);
            ToolProvisioning.Result result =
                    ToolProvisioning.provision(dist, registry, new Http(), /* noDiscover= */ false, refresh);
            switch (result.source()) {
                case LINKED -> notice.accept("Linked Kotlin " + dist.version() + " from " + result.detail());
                case DOWNLOADED -> notice.accept("Installed Kotlin " + dist.version() + " from " + result.detail());
                case CACHED -> {
                    /* silent */
                }
            }
            return result.tool().home();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("failed to provision Kotlin " + dist.version() + ": " + e.getMessage(), e);
        }
    }
}
