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
        DynamicSurface back = DynamicSurfaceIo.fromJson(json);
        assertThat(back.entries()).hasSize(2);
        assertThat(back.of(DynamicSurface.Kind.REFLECTIVE_TYPE).getFirst().name())
                .isEqualTo("com.example.A");
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
}
