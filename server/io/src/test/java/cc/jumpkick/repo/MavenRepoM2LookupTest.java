// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * a {@code stat} of {@code ~/.m2} before paying for an artifact.
 *
 * <p>The point of every test here is the integrity rule. {@code ~/.m2} is writable by anything on the
 * machine and Maven enforces no integrity, so a local hit is only ever a <em>candidate</em>; the
 * authority is a checksum fetched from the repository the artifact would have come from. Fetching it
 * remotely rather than reading {@code jk-lock.toml} is what makes the check usable during resolve, when no
 * lock entry exists yet.
 */
class MavenRepoM2LookupTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.jar";
    private static final byte[] REAL = "the genuine artifact bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI base;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        System.clearProperty("jk.m2.local");
        cc.jumpkick.config.SessionContext.reset();
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, ex -> {
            hits.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(status, body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private static String sha1Of(byte[] data) {
        return cc.jumpkick.util.Hashing.hashHex("SHA-1", data);
    }

    /** Put {@code bytes} at the coordinate's Maven-layout path inside a throwaway {@code ~/.m2}. */
    private static void seedM2(Path m2, byte[] bytes) throws IOException {
        System.setProperty("jk.m2.local", m2.toString());
        Path p = m2.resolve(REL);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private static Coordinate coord() {
        return Coordinate.of("com.example", "widget", "1.0");
    }

    @Test
    void a_local_hit_confirmed_by_the_remote_checksum_skips_the_jar_transfer(@TempDir Path tmp) throws Exception {
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        // Deliberately NO handler for the jar itself: reaching for it would 404 and fail the fetch, so a
        // pass here proves the bytes came from ~/.m2.
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
        assertThat(fetched.sha256()).isEqualTo(cc.jumpkick.util.Hashing.sha256Hex(REAL));
        assertThat(hits).containsExactly("/" + REL + ".sha1");
    }

    @Test
    void the_adopted_artifact_lands_in_the_store_the_same_as_a_download(@TempDir Path tmp) throws Exception {
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        Path store = tmp.resolve("store");
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(store));

        repo.fetchArtifact(coord());

        // Same CAS blob and same repos/<name>/ view a normal fetch would have produced, so nothing
        // downstream can tell the difference.
        assertThat(new Cas(store).contains(cc.jumpkick.util.Hashing.sha256Hex(REAL)))
                .isTrue();
        assertThat(store.resolve("repos/test").resolve(REL)).exists();
    }

    @Test
    void a_copy_is_made_by_default_so_the_blob_does_not_share_an_inode(@TempDir Path tmp) throws Exception {
        // The default is copy: a hard link would leave the CAS blob sharing an inode with a file jk does
        // not own, and any tool rewriting it in place would mutate content the CAS believes it hashed.
        Path m2 = tmp.resolve("m2");
        seedM2(m2, REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        Object blobKey = Files.readAttributes(fetched.cachePath(), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        Object m2Key = Files.readAttributes(m2.resolve(REL), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        assertThat(blobKey).isNotEqualTo(m2Key);
    }

    @Test
    void linking_is_opt_in_and_shares_the_inode(@TempDir Path tmp) throws Exception {
        // The override trades the copy for a shared inode. Asserting the inode really is shared is the
        // only way to know it does anything — a "link" mode that silently kept copying would look
        // identical from the outside.
        Path m2 = tmp.resolve("m2");
        seedM2(m2, REAL);
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched;
        System.setProperty("jk.m2.link", "true");
        try {
            fetched = repo.fetchArtifact(coord());
        } finally {
            System.clearProperty("jk.m2.link");
        }

        Object blobKey = Files.readAttributes(fetched.cachePath(), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        Object m2Key = Files.readAttributes(m2.resolve(REL), java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        assertThat(blobKey).isEqualTo(m2Key);
    }

    @Test
    void bytes_that_do_not_match_the_remote_checksum_are_refused(@TempDir Path tmp) throws Exception {
        // The case the whole design exists for: something at the right coordinate that is not the right
        // artifact. It must be ignored and the real one downloaded.
        seedM2(tmp.resolve("m2"), "a hand-built impostor".getBytes(StandardCharsets.UTF_8));
        serve("/" + REL + ".sha1", 200, sha1Of(REAL).getBytes(StandardCharsets.UTF_8));
        serve("/" + REL, 200, REAL);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
        assertThat(hits).contains("/" + REL); // the jar really was transferred
    }

    @Test
    void a_missing_sidecar_means_no_authority_so_the_local_file_is_unused(@TempDir Path tmp) throws Exception {
        // No.sha1 to confirm against — even though the local bytes happen to be correct, there is
        // nothing vouching for them, so they are not used.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL, 200, REAL);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        repo.fetchArtifact(coord());

        assertThat(hits).contains("/" + REL);
    }

    @Test
    void a_sidecar_that_is_not_a_sha1_is_refused(@TempDir Path tmp) throws Exception {
        // Some repositories answer 200 with an HTML error page. Forty hex characters or nothing.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8));
        serve("/" + REL, 200, REAL);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        repo.fetchArtifact(coord());

        assertThat(hits).contains("/" + REL);
    }

    @Test
    void a_checksum_with_a_trailing_filename_still_parses(@TempDir Path tmp) throws Exception {
        // `sha1sum` style: "<hex> <filename>". Common enough that rejecting it would silently disable
        // the optimisation against those repositories.
        seedM2(tmp.resolve("m2"), REAL);
        serve("/" + REL + ".sha1", 200, (sha1Of(REAL) + "  widget-1.0.jar\n").getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
        assertThat(hits).containsExactly("/" + REL + ".sha1");
    }

    @Test
    void nothing_in_m2_costs_one_stat_and_falls_through(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.m2.local", tmp.resolve("empty-m2").toString());
        serve("/" + REL, 200, REAL);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = repo.fetchArtifact(coord());

        // Falls straight through to the ordinary download. (The sidecar GETs visible here belong to the
        // normal post-download verification, not to the probe — with no candidate there is nothing to
        // confirm, and the hit-path test above proves the probe's own GET is the only request made when
        // a candidate does exist.)
        assertThat(hits).contains("/" + REL);
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(REAL);
    }
}
