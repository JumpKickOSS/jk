// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.engine.IsolatedStore;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The Maven-repository <em>documents</em> the CLI command tests need, on top of the shared
 * {@link LoopbackHttp} socket.
 *
 * <p>Register it with {@code @RegisterExtension} on an instance field: the inherited extension
 * binds an ephemeral 127.0.0.1 port before each test (so parallel suites never race over a port)
 * and stops after it. Responses come straight from the {@code served()} map — a test describes a
 * repository by seeding paths, not by writing handlers — and unknown paths get a 404 so a missing
 * fixture entry fails fast as a resolution error instead of hanging.
 *
 * <p><strong>Why this class still exists after {@code LoopbackHttp} took the plumbing.</strong>
 * Everything below is a document shape a <em>third party</em> emits: a Maven POM, a
 * {@code maven-metadata.xml}, a repository path layout. Those are hand-written here, deliberately
 * unlike what jk's own writers produce — see {@link #registerMetadata}. Only the transport is
 * shared, because the transport is not the thing under test.
 *
 * <p><strong>A coordinate is one class's.</strong> The classes of this module share one store and,
 * on the shared store, one engine. A stub's URL is fresh each start, but not everything jk keeps is
 * keyed by URL: the store's {@code jk-local} shelf and the Maven local repository are keyed by
 * coordinate, so two classes that serve {@code com.foo:leaf:1.0} with different bytes can each see
 * the other's, and which one wins depends on what ran first and when. After each
 * test this server checks the POMs and jars it served against every other class's in this JVM and
 * fails the test when another class served different bytes at the same path. The cure is a group of
 * the class's own ({@code com.foo.outdated}), a {@code MavenStub.freshVersion()}, or {@link
 * IsolatedStore}; a class or method on a private store ({@link IsolatedStore}, {@link IsolatedHome})
 * is not checked and claims nothing.
 */
public final class MockMavenServer extends LoopbackHttp {

    /** Which class first served each POM or jar path in this JVM, and the bytes' digest. */
    private static final Map<String, Claim> CLAIMS = new ConcurrentHashMap<>();

    private record Claim(String sha256, String testClass) {}

    /** The running test's class, or null when its store is its own and nothing is checked. */
    private @Nullable String claimant;

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        claimant = privateStore(context) ? null : context.getRequiredTestClass().getName();
        super.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            String owner = claimant;
            if (owner != null) claim(owner, served());
        } finally {
            claimant = null;
            super.afterEach(context);
        }
    }

    /**
     * Record {@code owner}'s POMs and jars, failing on the first path another class served with
     * different bytes. Checksum sidecars and {@code maven-metadata.xml} are left out: a sidecar is
     * derived from its artifact, and a version list is read only through this start's URL.
     */
    static void claim(String owner, Map<String, byte[]> served) {
        for (Map.Entry<String, byte[]> e : served.entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(".pom") && !path.endsWith(".jar")) continue;
            Claim mine = new Claim(Hashing.sha256Hex(e.getValue()), owner);
            Claim first = CLAIMS.putIfAbsent(path, mine);
            if (first == null
                    || first.testClass().equals(owner)
                    || first.sha256().equals(mine.sha256())) continue;
            throw new AssertionError(owner + " serves " + path + " with bytes other than " + first.testClass()
                    + " served there: the classes share a store, so each can read the other's. Give one a"
                    + " group of its own, a MavenStub.freshVersion(), or @IsolatedStore.");
        }
    }

    private static boolean privateStore(ExtensionContext context) {
        return isolated(context, IsolatedStore.class) || isolated(context, IsolatedHome.class);
    }

    private static boolean isolated(ExtensionContext context, Class<? extends Annotation> marker) {
        if (context.getTestMethod().map(m -> m.isAnnotationPresent(marker)).orElse(false)) return true;
        for (Class<?> c = context.getRequiredTestClass(); c != null; c = c.getEnclosingClass()) {
            if (c.isAnnotationPresent(marker)) return true;
        }
        return false;
    }

    /** Serve {@code body} as the pom of {@code group:artifact:version}. */
    public void registerPom(String group, String artifact, String version, String body) {
        served().put(mavenPath(group, artifact, version, "pom"), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Serve {@code bytes} as the jar of {@code group:artifact:version}. */
    public void registerJar(String group, String artifact, String version, byte[] bytes) {
        served().put(mavenPath(group, artifact, version, "jar"), bytes);
    }

    /**
     * Serve a compact {@code maven-metadata.xml} listing {@code versions} for the artifact.
     *
     * <p>Hand-written on purpose, and not routed through {@code MavenMetadata}:
     * this class impersonates a third-party Maven repository, which does not use jk's writer. The
     * shape here — no XML prolog, no {@code <latest>}/{@code <release>} — is deliberately unlike
     * what jk emits, and that is what makes it independent coverage of jk's reader. Generating the
     * fixture from the code under test would let a broken writer produce a document its own broken
     * reader accepts.
     */
    public void registerMetadata(String group, String artifact, String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served().put(
                        "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
                        xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Serve a dependency-free pom (full XML prolog form) for {@code group:artifact:version}. */
    public void servePom(String group, String artifact, String version) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        served().put(mavenPath(group, artifact, version, "pom"), pom.getBytes());
    }

    /** Minimal pom body with no dependencies; pair with {@link #registerPom}. */
    public static String pom(String group, String artifact, String version) {
        return pom(group, artifact, version, "");
    }

    /** Minimal pom body whose {@code <dependencies>} element wraps {@code depBlock} verbatim. */
    public static String pom(String group, String artifact, String version, String depBlock) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(group, artifact, version, depBlock);
    }

    /** Repository path of the {@code ext} artifact of {@code group:artifact:version}. */
    public static String mavenPath(String group, String artifact, String version, String ext) {
        return "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + "."
                + ext;
    }
}
