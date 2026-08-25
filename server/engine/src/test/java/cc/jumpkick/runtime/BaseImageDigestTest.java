// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A tag is a name, not an identity. These pin the two halves of turning one into the other: the
 * reference grammar every registry client shares, and the anonymous bearer dance a public registry
 * requires before it will answer at all.
 */
class BaseImageDigestTest {

    private static final String DIGEST = "sha256:" + "1".repeat(64);

    private HttpServer server;
    private String host;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        host = "127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * The whole point: {@code app:1.2} comes back as {@code app@sha256:…}, so the action key that
     * carries it changes the moment the registry republishes the tag.
     */
    @Test
    void a_tag_resolves_to_the_digest_the_registry_serves() {
        AtomicReference<String> manifestAuth = new AtomicReference<>();
        registryRequiringAToken(manifestAuth, true);

        Optional<String> pinned = BaseImageDigest.pin(host + "/acme/app:1.2", new Http());

        assertThat(pinned).contains(host + "/acme/app@" + DIGEST);
        assertThat(manifestAuth.get())
                .as("the 401 challenge has to be answered, or a public pull cannot be resolved at all")
                .isEqualTo("Bearer minted-token");
    }

    /** A registry that omits {@code Docker-Content-Digest}: the manifest bytes are the digest. */
    @Test
    void a_registry_that_omits_the_digest_header_is_hashed_instead() {
        registryRequiringAToken(new AtomicReference<>(), false);

        Optional<String> pinned = BaseImageDigest.pin(host + "/acme/app:1.2", new Http());

        // sha256 of the manifest body served below.
        assertThat(pinned)
                .contains(host + "/acme/app@sha256:"
                        + Hashing.sha256Hex("{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8)));
    }

    /** An unreachable registry resolves to nothing, so the caller can decline to cache. */
    @Test
    void an_unanswerable_registry_yields_no_digest() {
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });

        assertThat(BaseImageDigest.pin(host + "/acme/app:1.2", Http.failFast())).isEmpty();
    }

    /** An already-pinned reference is its own answer — no registry, no network, no failure mode. */
    @Test
    void a_digest_pinned_reference_needs_no_registry() {
        String pinned = "ghcr.io/acme/app@" + DIGEST;
        assertThat(BaseImageDigest.pin(pinned, Http.failFast())).contains(pinned);
    }

    /** The reference grammar Jib, docker and podman all share. */
    @Test
    void references_split_into_registry_repository_and_tag() {
        assertThat(BaseImageDigest.parse("eclipse-temurin:25-jre"))
                .isEqualTo(new BaseImageDigest.Ref("docker.io", "library/eclipse-temurin", "25-jre"));
        assertThat(BaseImageDigest.parse("bellsoft/liberica-runtime-container:jre-25"))
                .isEqualTo(new BaseImageDigest.Ref("docker.io", "bellsoft/liberica-runtime-container", "jre-25"));
        assertThat(BaseImageDigest.parse("ghcr.io/acme/app:1"))
                .isEqualTo(new BaseImageDigest.Ref("ghcr.io", "acme/app", "1"));
        assertThat(BaseImageDigest.parse("localhost:5000/app"))
                .as("a port in the first segment is a host, and an unqualified reference means :latest")
                .isEqualTo(new BaseImageDigest.Ref("localhost:5000", "app", "latest"));
    }

    /**
     * The Docker Hub / GHCR / registry:2 private shape: the realm mints a token only for a Basic
     * caller, and the credential itself goes to the realm and nowhere else — the manifest leg
     * carries the minted token, never the password.
     */
    @Test
    void a_private_registry_requiring_basic_at_the_token_endpoint_is_pinned() {
        String expectedBasic = basic("user", "secret");
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/token", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            requests.add("/token " + auth);
            if (!expectedBasic.equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"token\":\"minted-token\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            requests.add("/manifest " + auth);
            if (!"Bearer minted-token".equals(auth)) {
                ex.getResponseHeaders()
                        .add("WWW-Authenticate", "Bearer realm=\"http://" + host + "/token\",service=\"reg\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        Optional<String> pinned =
                BaseImageDigest.pin(host + "/acme/app:1.2", new Http(), new RepoCredential.Basic("user", "secret"));

        assertThat(pinned).contains(host + "/acme/app@" + DIGEST);
        assertThat(requests.stream().filter(r -> r.contains(expectedBasic)))
                .as("the password authenticates the challenge's realm and reaches nothing else")
                .containsExactly("/token " + expectedBasic);
    }

    /** The Artifactory / Nexus shape: no bearer realm, the manifest endpoint itself takes Basic. */
    @Test
    void a_basic_challenge_registry_is_pinned_with_basic_on_the_manifest() {
        String expectedBasic = basic("user", "secret");
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            if (!expectedBasic.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"registry\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        Optional<String> pinned =
                BaseImageDigest.pin(host + "/acme/app:1.2", new Http(), new RepoCredential.Basic("user", "secret"));

        assertThat(pinned).contains(host + "/acme/app@" + DIGEST);
    }

    /** A bearer credential is already the pull token: no realm round trip, straight manifest retry. */
    @Test
    void a_bearer_credential_goes_straight_onto_the_manifest_retry() {
        List<String> tokenHits = new CopyOnWriteArrayList<>();
        server.createContext("/token", ex -> {
            tokenHits.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            if (!"Bearer stored-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders()
                        .add("WWW-Authenticate", "Bearer realm=\"http://" + host + "/token\",service=\"reg\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        Optional<String> pinned =
                BaseImageDigest.pin(host + "/acme/app:1.2", new Http(), new RepoCredential.Bearer("stored-token"));

        assertThat(pinned).contains(host + "/acme/app@" + DIGEST);
        assertThat(tokenHits)
                .as("the realm has nothing to add to a credential that is a token")
                .isEmpty();
    }

    /** A rejected credential resolves to nothing, exactly like the anonymous 401 before it. */
    @Test
    void a_wrong_credential_yields_no_digest() {
        server.createContext("/token", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            ex.getResponseHeaders()
                    .add("WWW-Authenticate", "Bearer realm=\"http://" + host + "/token\",service=\"reg\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });

        assertThat(BaseImageDigest.pin(
                        host + "/acme/app:1.2", Http.failFast(), new RepoCredential.Basic("user", "wrong")))
                .isEmpty();
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A registry that answers 401 with a bearer challenge, mints a token at its realm, and serves
     * the manifest only to a caller carrying it — Docker Hub's behaviour for a public image.
     */
    private void registryRequiringAToken(AtomicReference<String> seenAuth, boolean sendDigestHeader) {
        server.createContext("/token", ex -> {
            byte[] body = "{\"token\":\"minted-token\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v2/acme/app/manifests/1.2", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seenAuth.set(auth);
            if (auth == null) {
                ex.getResponseHeaders()
                        .add(
                                "WWW-Authenticate",
                                "Bearer realm=\"http://" + host + "/token\",service=\"reg\","
                                        + "scope=\"repository:acme/app:pull\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
            if (sendDigestHeader) ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.getResponseHeaders().add("Content-Type", "application/vnd.oci.image.index.v1+json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }
}
