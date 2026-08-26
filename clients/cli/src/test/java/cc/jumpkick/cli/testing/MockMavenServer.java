// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;

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
 */
public final class MockMavenServer extends LoopbackHttp {

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
