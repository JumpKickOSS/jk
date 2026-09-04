// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A stub Maven repository laid over a {@link LoopbackHttp}: metadata, POMs, empty jars and sources
 * jars under the standard layout, so a lock test declares what upstream publishes in one line per
 * artifact. Paths are the repository-relative layout paths; the host and port come from the server.
 */
public final class MavenStub {

    /** A valid empty zip (a bare end-of-central-directory record); enough for a checksum. */
    public static final byte[] EMPTY_JAR = {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private final Map<String, byte[]> served;

    public MavenStub(LoopbackHttp http) {
        this(http.served());
    }

    /** Over any path-to-body map a stub server reads from. */
    public MavenStub(Map<String, byte[]> served) {
        this.served = served;
    }

    /** Metadata listing exactly {@code version}, an empty POM, and an empty jar. */
    public MavenStub leaf(String group, String artifact, String version) {
        return metadata(group, artifact, version).pom(group, artifact, version, emptyPom(group, artifact, version));
    }

    /** {@code maven-metadata.xml} listing {@code versions} in the order given. */
    public MavenStub metadata(String group, String artifact, String... versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        return text(metadataPath(group, artifact), body.toString());
    }

    /** The POM as given, plus an empty jar unless the POM declares {@code <packaging>pom</packaging>}. */
    public MavenStub pom(String group, String artifact, String version, String body) {
        text(path(group, artifact, version, ".pom"), body);
        if (!body.contains("<packaging>pom</packaging>")) jar(group, artifact, version);
        return this;
    }

    /** The POM only; a lock that needs the jar then fails to fetch it. */
    public MavenStub pomWithoutJar(String group, String artifact, String version) {
        return pomOnly(group, artifact, version, emptyPom(group, artifact, version));
    }

    /** The POM as given and nothing else — for solver tests that never materialize. */
    public MavenStub pomOnly(String group, String artifact, String version, String body) {
        return text(path(group, artifact, version, ".pom"), body);
    }

    public MavenStub jar(String group, String artifact, String version) {
        return bytes(path(group, artifact, version, ".jar"), EMPTY_JAR);
    }

    public MavenStub sourcesJar(String group, String artifact, String version) {
        return bytes(path(group, artifact, version, "-sources.jar"), EMPTY_JAR);
    }

    public MavenStub text(String path, String body) {
        return bytes(path, body.getBytes(StandardCharsets.UTF_8));
    }

    public MavenStub bytes(String path, byte[] body) {
        served.put(path, body);
        return this;
    }

    /** {@code /g/r/o/u/p/artifact/version/artifact-version<suffix>}. */
    public static String path(String group, String artifact, String version, String suffix) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + suffix;
    }

    public static String metadataPath(String group, String artifact) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
    }

    /** A POM with coordinates and nothing else. */
    public static String emptyPom(String group, String artifact, String version) {
        return "<project><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version></project>";
    }

    /** A {@code <packaging>pom</packaging>} BOM managing {@code module -> version} pairs. */
    public static String bom(String group, String artifact, String version, List<String> managed) {
        StringBuilder b = new StringBuilder();
        b.append("<project><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><version>")
                .append(version)
                .append("</version><packaging>pom</packaging><dependencyManagement><dependencies>");
        for (String m : managed) {
            String[] gav = m.split(":");
            b.append("<dependency><groupId>")
                    .append(gav[0])
                    .append("</groupId><artifactId>")
                    .append(gav[1])
                    .append("</artifactId><version>")
                    .append(gav[2])
                    .append("</version></dependency>");
        }
        return b.append("</dependencies></dependencyManagement></project>").toString();
    }
}
