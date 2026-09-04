// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the JDK home for a Java launch: project pin via {@link JdkResolution#resolveForHook},
 * else {@link #runningJavaHome()}. Shared by CLI exec paths and engine compile so they agree.
 */
public final class JavaHomes {

    private JavaHomes() {}

    public static Path resolveJavaHome(Path projectDir) {
        return resolveJavaHome(projectDir, new JdkRegistry());
    }

    /**
     * As {@link #resolveJavaHome(Path)} with a caller-owned registry, so one probe scan serves
     * both this walk and whatever the caller does next with the same registry.
     */
    public static Path resolveJavaHome(Path projectDir, JdkRegistry registry) {
        try {
            Lockfile lock = readLockSoft(projectDir);
            JkBuild build = readBuildSoft(projectDir);
            // The request's environment, not the daemon's.
            Function<String, @Nullable String> env = BuildEnv.forModule(projectDir);
            JdkResolution.Request req = new JdkResolution.Request(
                    projectDir,
                    SessionContext.current().jdkSpec(),
                    null,
                    lock == null ? null : lock.jdk(),
                    (build != null && build.project() != null) ? build.project().jdk() : null,
                    (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                    env::apply);
            // Non-installing walk of the canonical order — JdkEnsure already
            // installed any pin during sync, so this just locates it. Falls back
            // to the running JVM when nothing resolves.
            JdkResolution.Resolved r = JdkResolution.resolveForHook(req, registry, JdkInventory.current());
            if (r.jdk().isPresent()) return r.jdk().get().home();
        } catch (RuntimeException ignored) {
            // fall through to the running JVM
        }
        return runningJavaHome();
    }

    private static Lockfile readLockSoft(Path projectDir) {
        try {
            Path lock = LockPaths.lockFile(projectDir);
            return Files.isRegularFile(lock) ? LockfileReader.read(lock) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Bootstrap jdk/java pins for {@code projectDir}, workspace-inherited. Test-visible. */
    static JkBuild readBuildSoft(Path projectDir) {
        try {
            Path toml = projectDir.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(toml)) return null;
            var scan = TomlScan.scan(toml, "jdk", "java");
            String jdk = scan.get("jdk");
            String java = scan.get("java");
            if (isBlank(jdk) || isBlank(java)) {
                // A workspace member auto-inherits jdk/java from its root. Mirror it per key —
                // same bootstrap pattern as ProjectIdentity.coordOf's group inheritance.
                var root = WorkspaceScan.findRoot(projectDir);
                if (root.isPresent()) {
                    var rootScan = TomlScan.scan(root.get().resolve(ManifestPaths.MANIFEST), "jdk", "java");
                    if (isBlank(jdk)) jdk = rootScan.get("jdk");
                    if (isBlank(java)) java = rootScan.get("java");
                }
            }
            int release = 0;
            if (java != null && !java.isBlank()) {
                try {
                    release = Integer.parseInt(java.strip());
                } catch (NumberFormatException ignored) {
                    // leave 0 — resolver falls back
                }
            }
            return JkBuild.of(Project.builder("local", "local", "0")
                    .jdk(jdk)
                    .java(release)
                    .build());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The JDK that's hosting the current jk process, or {@code $JAVA_HOME} when running under GraalVM
     * native-image (which doesn't expose {@code java.home}). Used as the last-resort fallback when no
     * project JDK is pinned; throws an explanatory error if neither is available.
     */
    public static Path runningJavaHome() {
        String home = System.getProperty("java.home");
        // Ambient on purpose, and not the defect: the question is which JVM *this process*
        // runs on, not which JDK the request asked for. Inside the engine `java.home` is always set,
        // so the fallback only fires in the native CLI — where the process is the caller's shell and
        // its own environment is the right answer.
        if (home == null || home.isBlank()) home = System.getenv("JAVA_HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("Cannot resolve a JDK: no project pin (`.jdk-version` or `.sdkmanrc`), "
                    + "no `java.home` (running under native-image?), and `JAVA_HOME` is unset. "
                    + "Pin a JDK with a `.jdk-version` file, set `JAVA_HOME`, or run jk on a JVM.");
        }
        return Path.of(home);
    }
}
