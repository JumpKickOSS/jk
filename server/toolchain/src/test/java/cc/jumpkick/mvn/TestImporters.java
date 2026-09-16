// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Importers for tests: one that can reach no parent at all, one over a loopback repository. */
public final class TestImporters {

    private TestImporters() {}

    /** An importer whose only repository is an empty directory, so every external parent is missing. */
    public static PomImporter offline(Path tempDir) throws IOException {
        Path empty = Files.createDirectories(tempDir.resolve("no-repo"));
        return over(tempDir, empty.toUri());
    }

    /** An importer resolving parents and BOMs from {@code repo} (a loopback server or a directory). */
    public static PomImporter over(Path tempDir, URI repo) {
        return over(tempDir, repo, uri -> {
            throw new IOException("no remote files in this test: " + uri);
        });
    }

    /** {@link #over(Path, URI)} with {@code remote} answering the URLs a generator plugin's spec names. */
    public static PomImporter over(Path tempDir, URI repo, PomImporter.RemoteFile remote) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return new PomImporter(RepoGroup.of(new MavenRepo("fixture", repo, new Http(), cas)), cas, remote);
    }

    /** Maven-layout path of a POM under a repository root. */
    public static String pomPath(String group, String artifact, String version) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom";
    }

    /** Import a fixture POM offline, written under {@code tempDir/project} so relative paths have a base. */
    public static PomImporter.Result importFixture(Path tempDir, String dir, String file) throws IOException {
        return importXml(tempDir, fixture(dir, file));
    }

    /** Import an inline POM offline, written under {@code tempDir/project}. */
    public static PomImporter.Result importXml(Path tempDir, String xml) throws IOException {
        return importXml(tempDir, xml, uri -> {
            throw new IOException("no remote files in this test: " + uri);
        });
    }

    /** {@link #importXml(Path, String)} with {@code remote} answering a generator plugin's spec URL. */
    public static PomImporter.Result importXml(Path tempDir, String xml, PomImporter.RemoteFile remote)
            throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, xml, StandardCharsets.UTF_8);
        Path empty = Files.createDirectories(tempDir.resolve("no-repo"));
        return over(tempDir, empty.toUri(), remote).importFrom(pom);
    }

    /** Every report row's text, in order. */
    public static List<String> messages(PomImporter.Result result) {
        return result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
    }

    /** A hand-written POM fixture from {@code cc/jumpkick/mvn/<dir>/<file>} on the test classpath. */
    public static String fixture(String dir, String file) throws IOException {
        String path = "/cc/jumpkick/mvn/" + dir + "/" + file;
        try (InputStream in = Objects.requireNonNull(TestImporters.class.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
