// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.CompilerProtocol;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code snapshot} op reports, per classpath entry, the digest of the snapshot file the
 * incremental compile will read — the engine keys a Kotlin compile on those digests. The Build
 * Tools API implementation is not on this test classpath; the snapshotting call is the seam.
 */
class SnapshotOpTest {

    private static final String PREFIX = "##JKKC:";

    /** Writes a snapshot whose bytes describe the entry's content, as a real ABI snapshot would. */
    private static final class ContentSnapshotter implements KotlinCompiler.Snapshotter {
        final List<Path> requested = new ArrayList<>();

        @Override
        public void snapshot(Path entry, Path out) throws IOException {
            requested.add(entry);
            Files.writeString(out, "abi of " + Files.readString(entry, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    @Test
    void reports_the_digest_of_each_entrys_snapshot_bytes(@TempDir Path tmp) throws Exception {
        Path lib = write(tmp.resolve("lib.jar"), "lib v1");
        Path dep = write(tmp.resolve("dep.jar"), "dep v1");
        Path snapshots = tmp.resolve("snapshots");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        KotlinCompiler.snapshotEntries(
                List.of(lib.toFile(), dep.toFile()), snapshots, new ContentSnapshotter(), proto(wire));

        Map<String, String> reported = reported(wire);
        assertThat(reported)
                .containsOnlyKeys(
                        lib.toAbsolutePath().toString(), dep.toAbsolutePath().toString());
        assertThat(reported.get(lib.toAbsolutePath().toString()))
                .isEqualTo(Hashing.sha256Hex("abi of lib v1".getBytes(StandardCharsets.UTF_8)));
        assertThat(reported.get(dep.toAbsolutePath().toString()))
                .isEqualTo(Hashing.sha256Hex("abi of dep v1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void the_same_abi_at_two_paths_reports_the_same_digest(@TempDir Path tmp) throws Exception {
        Path a = write(tmp.resolve("a/lib.jar"), "same abi");
        Path b = write(tmp.resolve("b/lib.jar"), "same abi");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        KotlinCompiler.snapshotEntries(
                List.of(a.toFile(), b.toFile()), tmp.resolve("snapshots"), new ContentSnapshotter(), proto(wire));

        Map<String, String> reported = reported(wire);
        assertThat(reported).hasSize(2);
        assertThat(reported.get(a.toAbsolutePath().toString()))
                .isEqualTo(reported.get(b.toAbsolutePath().toString()));
    }

    @Test
    void a_missing_or_failing_entry_is_left_unreported_and_the_others_still_are(@TempDir Path tmp) throws Exception {
        Path present = write(tmp.resolve("present.jar"), "present");
        Path broken = write(tmp.resolve("broken.jar"), "broken");
        File absent = tmp.resolve("absent.jar").toFile();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        KotlinCompiler.Snapshotter snapshotter = (entry, out) -> {
            if (entry.equals(broken)) throw new IOException("cannot read " + entry);
            Files.writeString(out, "abi", StandardCharsets.UTF_8);
        };

        KotlinCompiler.snapshotEntries(
                List.of(absent, broken.toFile(), present.toFile()), tmp.resolve("snapshots"), snapshotter, proto(wire));

        assertThat(reported(wire))
                .as("only the entry that has a snapshot is reported; the engine keys the rest on content")
                .containsOnlyKeys(present.toAbsolutePath().toString());
    }

    @Test
    void the_compile_op_reads_the_snapshots_the_snapshot_op_wrote(@TempDir Path tmp) throws Exception {
        Path lib = write(tmp.resolve("lib.jar"), "lib");
        Path snapshots = tmp.resolve("snapshots");
        ContentSnapshotter snapshotter = new ContentSnapshotter();

        KotlinCompiler.snapshotEntries(
                List.of(lib.toFile()), snapshots, snapshotter, proto(new ByteArrayOutputStream()));
        List<Path> forCompile = KotlinCompiler.snapshotClasspath(List.of(lib.toFile()), snapshots, snapshotter);

        assertThat(snapshotter.requested)
                .as("one snapshotting pass serves both ops")
                .containsExactly(lib);
        assertThat(forCompile).hasSize(1);
        assertThat(Hashing.sha256Hex(forCompile.getFirst()))
                .isEqualTo(Hashing.sha256Hex("abi of lib".getBytes(StandardCharsets.UTF_8)));
    }

    private static CompilerProtocol proto(ByteArrayOutputStream wire) {
        return new CompilerProtocol(new ProtocolWriter(new PrintStream(wire, true, StandardCharsets.UTF_8), PREFIX));
    }

    /** The {@code cp-snapshot} replies on the wire, entry path to digest. */
    private static Map<String, String> reported(ByteArrayOutputStream wire) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : wire.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith(PREFIX)) continue;
            String json = line.substring(PREFIX.length());
            if (!PluginProtocol.CP_SNAPSHOT.equals(Jsonl.str(json, PluginProtocol.T))) continue;
            out.put(
                    String.valueOf(Jsonl.str(json, PluginProtocol.PATH)),
                    String.valueOf(Jsonl.str(json, PluginProtocol.SHA256)));
        }
        return out;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
