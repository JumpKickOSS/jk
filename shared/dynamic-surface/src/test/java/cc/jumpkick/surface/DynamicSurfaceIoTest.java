// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicSurfaceIoTest {

    @Test
    void round_trip_json() {
        DynamicSurface s = DynamicSurface.of(
                DynamicSurface.Entry.type(DynamicSurface.Kind.REFLECTIVE_TYPE, "com.example.A", "train:default"),
                DynamicSurface.Entry.type(DynamicSurface.Kind.RESOURCE, "config/*.xml", "index"));
        String json = DynamicSurfaceIo.toJson(s);
        assertThat(json).contains("\"version\": 2");
        DynamicSurface back = DynamicSurfaceIo.fromJson(json);
        assertThat(back.entries()).hasSize(2);
        assertThat(back.of(DynamicSurface.Kind.REFLECTIVE_TYPE).getFirst().name())
                .isEqualTo("com.example.A");
    }

    @Test
    void older_and_newer_format_versions_still_read() {
        // v1: untagged members (unknown kind) — must keep reading. Newer-than-current versions
        // read leniently too: unknown kinds are skipped, the rest is preserved (JK-1802).
        String v1 = """
                {"version": 1, "entries": [
                  {"kind":"REFLECTIVE_MEMBER","name":"com.example.A","origin":"t","members":["run"]}
                ]}
                """;
        DynamicSurface old = DynamicSurfaceIo.fromJson(v1);
        assertThat(old.entries()).singleElement().satisfies(e -> assertThat(e.members()).containsExactly("run"));

        String v9 = """
                {"version": 9, "entries": [
                  {"kind":"REFLECTIVE_TYPE","name":"com.example.B","origin":"t"},
                  {"kind":"KIND_FROM_THE_FUTURE","name":"com.example.C","origin":"t"}
                ]}
                """;
        DynamicSurface future = DynamicSurfaceIo.fromJson(v9);
        assertThat(future.entries()).singleElement().satisfies(e -> assertThat(e.name())
                .isEqualTo("com.example.B"));
    }

    @Test
    void import_agent_dir(@TempDir Path tmp) throws Exception {
        Path agent = tmp.resolve("agent");
        Files.createDirectories(agent);
        Files.writeString(agent.resolve("reflect-config.json"), """
                [
                  {"name":"com.example.Reflected","allDeclaredConstructors":true}
                ]
                """);
        DynamicSurface s = DynamicSurfaceIo.importAgentDir(agent, "train:p");
        assertThat(s.entries()).isNotEmpty();
        assertThat(s.of(DynamicSurface.Kind.REFLECTIVE_TYPE).stream().map(DynamicSurface.Entry::name))
                .contains("com.example.Reflected");
    }

    @Test
    void write_reachability_dir(@TempDir Path tmp) throws Exception {
        DynamicSurface s =
                DynamicSurface.of(DynamicSurface.Entry.type(DynamicSurface.Kind.REFLECTIVE_TYPE, "com.example.A", "t"));
        Path dir = tmp.resolve("reachability");
        DynamicSurfaceIo.writeReachabilityDir(dir, s);
        assertThat(dir.resolve("reachability-metadata.json")).exists();
        assertThat(Files.readString(dir.resolve("reachability-metadata.json"))).contains("com.example.A");
    }

    @Test
    void write_reachability_dir_writes_and_clears_the_pattern_sidecar(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("reachability");
        DynamicSurface withPattern = DynamicSurface.of(
                DynamicSurface.Entry.type(DynamicSurface.Kind.RESOURCE_PATTERN, ".*[.]properties$", "lib"));
        DynamicSurfaceIo.writeReachabilityDir(dir, withPattern);
        assertThat(dir.resolve("resource-config.json")).exists();
        assertThat(Files.readString(dir.resolve("resource-config.json"))).contains(".*[.]properties$");

        // A rewrite without patterns must not leave the previous run's sidecar behind.
        DynamicSurfaceIo.writeReachabilityDir(dir, DynamicSurface.empty());
        assertThat(dir.resolve("resource-config.json")).doesNotExist();
    }
}
