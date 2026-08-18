// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the JDK home for a Java launch: project pin via {@link JdkResolution#resolveForHook},
 * else {@link #runningJavaHome()}. Shared by CLI exec paths and engine compile so they agree.
 */
public final class JavaHomes {

    private JavaHomes() {}

    public static Path resolveJavaHome(Path projectDir) {
        try {
            cc.jumpkick.lock.Lockfile lock = readLockSoft(projectDir);
            JkBuild build = readBuildSoft(projectDir);
            JdkResolution.Request req = new JdkResolution.Request(
                    projectDir,
                    SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    (build != null && build.project() != null) ? build.project().jdk() : null,
                    (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                    System::getenv);
            // Non-installing walk of the canonical order — JdkEnsure already
            // installed any pin during sync, so this just locates it. Falls back
            // to the running JVM when nothing resolves.
            JdkResolution.Resolved r = JdkResolution.resolveForHook(req, new JdkRegistry(), GlobalDefaultJdk.current());
            if (r.jdk().isPresent()) return r.jdk().get().home();
        } catch (RuntimeException ignored) {
            // fall through to the running JVM
        }
        return runningJavaHome();
    }

    private static cc.jumpkick.lock.Lockfile readLockSoft(Path projectDir) {
        try {
            Path lock = cc.jumpkick.lock.LockPaths.lockFile(projectDir);
            return Files.isRegularFile(lock) ? cc.jumpkick.lock.LockfileReader.read(lock) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JkBuild readBuildSoft(Path projectDir) {
        try {
            Path toml = projectDir.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) return null;
            var scan = cc.jumpkick.config.TomlScan.scan(toml, "jdk", "java");
            String jdk = scan.get("jdk");
            String java = scan.get("java");
            int release = 0;
            if (java != null && !java.isBlank()) {
                try {
                    release = Integer.parseInt(java.strip());
                } catch (NumberFormatException ignored) {
                    // leave 0 — resolver falls back
                }
            }
            return JkBuild.of(JkBuild.Project.builder("local", "local", "0")
                    .jdk(jdk)
                    .java(release)
                    .build());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The JDK that's hosting the current jk process, or {@code $JAVA_HOME} when running under GraalVM
     * native-image (which doesn't expose {@code java.home}). Used as the last-resort fallback when no
     * project JDK is pinned; throws an explanatory error if neither is available.
     */
    public static Path runningJavaHome() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) home = System.getenv("JAVA_HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("Cannot resolve a JDK: no project pin (`.jdk-version` or `.sdkmanrc`), "
                    + "no `java.home` (running under native-image?), and `JAVA_HOME` is unset. "
                    + "Pin a JDK with a `.jdk-version` file, set `JAVA_HOME`, or run jk on a JVM.");
        }
        return Path.of(home);
    }
}
