// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Cas cas = new Cas(tempDir.resolve("cache"));
        return new PomImporter(RepoGroup.of(new MavenRepo("fixture", repo, new Http(), cas)), cas);
    }

    /** Maven-layout path of a POM under a repository root. */
    public static String pomPath(String group, String artifact, String version) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom";
    }

    /** A hand-written POM fixture from {@code cc/jumpkick/mvn/<dir>/<file>} on the test classpath. */
    public static String fixture(String dir, String file) throws IOException {
        String path = "/cc/jumpkick/mvn/" + dir + "/" + file;
        try (InputStream in = Objects.requireNonNull(TestImporters.class.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
