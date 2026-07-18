// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * jk injects the latest-stable JUnit Platform into every project's TEST scope (see {@code
 * LockOrchestrator}), so any test that runs a real {@code jk lock}/{@code jk build} against a mock
 * Maven server must offer those coords. This seeds them with minimal, dependency-free POMs + stub
 * jars so resolution succeeds without pulling the real JUnit closure.
 *
 * <p>Lock artifact names are package keys ({@code group:artifact:type:classifier}); helpers expose
 * GA form for assertions where tests care about modules, not packaging identity.
 */
final class DefaultTestDepsFixture {

    /** The version the mock repo advertises as JUnit's latest stable. */
    static final String JUNIT_VERSION = "6.1.0";

    /** GA form of the always-injected test defaults. */
    static final String JUPITER_GA = "org.junit.jupiter:junit-jupiter";

    static final String LAUNCHER_GA = "org.junit.platform:junit-platform-launcher";

    /** Lockfile package keys for the always-injected test defaults. */
    static final String JUPITER = PackageId.ofGa(JUPITER_GA).key();

    static final String LAUNCHER = PackageId.ofGa(LAUNCHER_GA).key();

    private DefaultTestDepsFixture() {}

    /**
     * Artifact identities in {@code lock}, minus the always-injected JUnit defaults, as
     * {@code group:artifact} (strips default {@code :jar:}).
     */
    static List<String> projectCoords(Lockfile lock) {
        return lock.artifacts().stream()
                .map(Lockfile.Artifact::packageKey)
                .filter(n -> !isDefaultTestDep(n))
                .map(DefaultTestDepsFixture::toGa)
                .toList();
    }

    static boolean isDefaultTestDep(String nameOrKey) {
        String ga = toGa(nameOrKey);
        return JUPITER_GA.equals(ga) || LAUNCHER_GA.equals(ga);
    }

    /** Normalize a lock name / package key to {@code group:artifact}. */
    static String toGa(String nameOrKey) {
        if (nameOrKey == null || nameOrKey.isBlank()) return nameOrKey;
        if (PackageId.isMavenPackageKey(nameOrKey)) {
            try {
                return PackageId.parse(nameOrKey).ga();
            } catch (RuntimeException ignored) {
                return nameOrKey;
            }
        }
        return nameOrKey;
    }

    /** Find an artifact by GA or full package key. */
    static Lockfile.Artifact pkg(Lockfile lock, String moduleOrKey) {
        String want = toGa(moduleOrKey);
        return lock.artifacts().stream()
                .filter(p -> toGa(p.packageKey()).equals(want) || toGa(p.name()).equals(want))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no package " + moduleOrKey + " in lock"));
    }

    /** Register junit-jupiter + junit-platform-launcher into a test's {@code served} map. */
    static void seed(Map<String, byte[]> served) {
        seedArtifact(served, "org.junit.jupiter", "junit-jupiter", JUNIT_VERSION);
        seedArtifact(served, "org.junit.platform", "junit-platform-launcher", JUNIT_VERSION);
    }

    private static void seedArtifact(Map<String, byte[]> served, String group, String artifact, String version) {
        String base = "/" + group.replace('.', '/') + "/" + artifact;
        put(
                served,
                base + "/maven-metadata.xml",
                "<metadata><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><versioning><versions><version>"
                        + version
                        + "</version></versions></versioning></metadata>");
        String dir = base + "/" + version + "/" + artifact + "-" + version;
        put(
                served,
                dir + ".pom",
                "<project><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><version>"
                        + version
                        + "</version></project>");
        served.put(dir + ".jar", (artifact + "-stub").getBytes(StandardCharsets.UTF_8));
    }

    private static void put(Map<String, byte[]> served, String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
