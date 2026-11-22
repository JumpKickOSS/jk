// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.updates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The update manifest's format, its one signature, the checksum each file is held to, and the state beside it. */
class UpdateManifestTest {

    private static final String SHA = "a".repeat(64);

    private static UpdateManifest parse(String json) throws IOException {
        return UpdateManifest.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reads_each_kind_and_skips_what_it_does_not_know() throws IOException {
        UpdateManifest m = parse("""
                {
                  "published": "2026-11-22T04:00:00Z",
                  "jk": {"stable": {"version": "0.60.0"}},
                  "data": {
                    "prices": {"version": "2026-11-21T18:00:00Z", "file": "prices.toml", "sha256": "%s", "size": 9},
                    "templates": {"version": "0123456789abcdef0123456789abcdef01234567"}
                  }
                }
                """.formatted(SHA));

        assertThat(m.published()).isEqualTo(Instant.parse("2026-11-22T04:00:00Z"));
        assertThat(m.entry("prices")).isEqualTo(new UpdateManifest.Entry("2026-11-21T18:00:00Z", "prices.toml", SHA));
        assertThat(m.entry("templates")).extracting(UpdateManifest.Entry::file).isNull();
        assertThat(m.data()).containsOnlyKeys("prices", "templates");
    }

    @Test
    void names_each_channels_release_and_skips_a_channel_it_cannot_read() throws IOException {
        UpdateManifest m = parse("""
                {
                  "published": "2026-11-22T04:00:00Z",
                  "jk": {
                    "stable": {"version": "0.60.0", "sha256": "%1$s"},
                    "nightly": {"version": "0.61.0-SNAPSHOT", "sha256": "%1$s"},
                    "beta": {"version": "../0.61.0", "sha256": "%1$s"},
                    "edge": {"version": "0.61.0"}
                  }
                }
                """.formatted(SHA));

        assertThat(m.release()).contains(new UpdateManifest.Release("0.60.0", SHA));
        assertThat(m.jk()).containsOnlyKeys("stable");
        assertThat(parse("{\"published\": \"2026-11-22T04:00:00Z\", \"jk\": []}")
                        .release())
                .isEmpty();
    }

    @Test
    void a_release_is_trusted_only_when_its_checksums_match_the_manifest() throws IOException {
        byte[] sums = "abc  jk-engine-0.60.0.jar\n".getBytes(StandardCharsets.UTF_8);
        new UpdateManifest.Release("0.60.0", Hashing.sha256Hex(sums)).verifySums(sums);

        assertThatThrownBy(() -> new UpdateManifest.Release("0.60.0", SHA).verifySums(sums))
                .hasMessageContaining("does not match");
    }

    @Test
    void the_held_manifest_is_read_from_the_state_directory(@TempDir Path dir) throws IOException {
        assertThat(UpdateManifest.held(dir)).isEmpty();
        Files.writeString(dir.resolve(UpdateManifest.FILE), "not json");
        assertThat(UpdateManifest.held(dir)).isEmpty();
        Files.writeString(
                dir.resolve(UpdateManifest.FILE),
                "{\"published\": \"2026-11-22T04:00:00Z\", \"jk\": {\"stable\": {\"version\": \"0.60.0\", "
                        + "\"sha256\": \"" + SHA + "\"}}}");
        assertThat(UpdateManifest.held(dir).flatMap(UpdateManifest::release))
                .contains(new UpdateManifest.Release("0.60.0", SHA));
    }

    @Test
    void refuses_what_is_not_a_manifest() {
        String entry = "{\"published\": \"2026-11-22T04:00:00Z\", \"data\": {\"prices\": %s}}";
        List<String> bad = List.of(
                "[]",
                "{\"data\": {}}",
                "{\"published\": \"yesterday\"}",
                "{\"published\": \"2026-11-22T04:00:00Z\", \"data\": []}",
                "{\"published\": \"2026-11-22T04:00:00Z\", \"data\": {\"Bad Kind\": {\"version\": \"1\"}}}",
                entry.formatted("{}"),
                entry.formatted("{\"version\": \"1\", \"file\": \"prices.toml\"}"),
                entry.formatted("{\"version\": \"1\", \"file\": \"prices.toml\", \"sha256\": \"ABC\"}"),
                entry.formatted("{\"version\": \"1\", \"file\": \"../prices.toml\", \"sha256\": \"" + SHA + "\"}"),
                entry.formatted("{\"version\": \"1\", \"file\": \"/etc/passwd\", \"sha256\": \"" + SHA + "\"}"),
                entry.formatted("{\"version\": \"1\", \"file\": \"https://evil/x\", \"sha256\": \"" + SHA + "\"}"));
        for (String json : bad) {
            assertThatThrownBy(() -> parse(json)).as(json).isInstanceOf(IOException.class);
        }
    }

    @Test
    void a_file_is_trusted_only_when_it_matches_its_sha256() throws IOException {
        byte[] bytes = "rows".getBytes(StandardCharsets.UTF_8);
        new UpdateManifest.Entry("1", "f", Hashing.sha256Hex(bytes)).verify(bytes);

        assertThatThrownBy(() -> new UpdateManifest.Entry("1", "f", SHA).verify(bytes))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> new UpdateManifest.Entry("1", null, null).verify(bytes))
                .isInstanceOf(IOException.class);
    }

    @Test
    void the_signature_covers_the_manifest_bytes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair key = gen.generateKeyPair();
        ReleaseVerifier verifier = ReleaseVerifier.of(
                List.of(Base64.getEncoder().encodeToString(key.getPublic().getEncoded())));
        byte[] json = "{\"published\": \"2026-11-22T04:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(json);
        byte[] sig = Base64.getEncoder().encodeToString(signer.sign()).getBytes(StandardCharsets.US_ASCII);

        assertThat(UpdateManifest.verified(json, sig, verifier).data()).isEmpty();
        byte[] tampered = "{\"published\": \"2026-11-23T04:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> UpdateManifest.verified(tampered, sig, verifier))
                .isInstanceOf(IOException.class);
    }

    @Test
    void the_state_round_trips_and_an_unreadable_one_is_none(@TempDir Path dir) throws IOException {
        assertThat(UpdateState.read(dir)).isEqualTo(UpdateState.NONE);
        Instant at = Instant.parse("2026-11-22T05:00:00Z");
        UpdateState state = UpdateState.NONE
                .checked(at, "up to date")
                .withValidators("\"e1\"", "Sat, 22 Nov 2026 04:00:00 GMT")
                .withPending(Map.of("prices", "v2", "libraries", "r9"))
                .withApplied("prices", "v2");

        state.write(dir);

        assertThat(UpdateState.read(dir)).isEqualTo(state);
        assertThat(state.pending()).containsOnlyKeys("libraries");
        Files.writeString(dir.resolve(UpdateState.FILE), "{not json");
        assertThat(UpdateState.read(dir)).isEqualTo(UpdateState.NONE);
    }
}
