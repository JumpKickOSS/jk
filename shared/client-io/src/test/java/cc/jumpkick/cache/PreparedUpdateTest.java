// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedUpdateTest {

    private static final Instant AT = Instant.parse("2026-10-30T14:02:11Z");

    @Test
    void a_record_round_trips_and_deletes(@TempDir Path home) throws Exception {
        var record = new PreparedUpdate(
                "0.17.0", "0.16.2", "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), AT);
        record.write(home);

        assertThat(PreparedUpdate.read(home)).contains(record);
        assertThat(Files.readString(PreparedUpdate.file(home))).contains("client = \"native\"");

        PreparedUpdate.delete(home);
        assertThat(PreparedUpdate.read(home)).isEmpty();
    }

    @Test
    void a_record_missing_a_field_reads_as_none(@TempDir Path home) throws Exception {
        Files.writeString(PreparedUpdate.file(home), "version = \"0.17.0\"\nfrom = \"0.16.2\"\n");
        assertThat(PreparedUpdate.read(home)).isEmpty();
    }

    @Test
    void newer_compares_with_the_product_version_order() {
        var record =
                new PreparedUpdate("0.17.0", "0.16.2", "a".repeat(64), PreparedUpdate.Client.JVM, "b".repeat(64), AT);
        assertThat(record.newerThan("0.16.2")).isTrue();
        assertThat(record.newerThan("0.17.0")).isFalse();
        assertThat(record.newerThan("0.18.0")).isFalse();
    }

    @Test
    void blobs_are_intact_only_while_the_cas_holds_their_bytes(@TempDir Path dir) throws Exception {
        var cas = new Cas(dir.resolve("cas"));
        byte[] engine = "engine".getBytes(StandardCharsets.UTF_8);
        byte[] client = "client".getBytes(StandardCharsets.UTF_8);
        String engineSha = Hashing.sha256Hex(engine);
        String clientSha = Hashing.sha256Hex(client);
        cas.put(engine, engineSha);
        var record = new PreparedUpdate("0.17.0", "0.16.2", engineSha, PreparedUpdate.Client.NATIVE, clientSha, AT);

        assertThat(record.blobsIntact(cas)).isFalse();
        cas.put(client, clientSha);
        assertThat(record.blobsIntact(cas)).isTrue();
    }
}
