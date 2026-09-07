// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardModelSnapshotTest {

    @Test
    @SuppressWarnings("unchecked")
    void the_snapshot_carries_modules_deps_tiers_and_toolchain(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25
                java = 21

                [workspace]
                modules = ["app", "lib"]

                [test]
                exclude-tags = ["slow"]

                [profiles.slow]
                include-tags = ["slow"]

                [repositories]
                corp = { url = "https://corp.example/m2/" }
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/jk.toml"), """
                group = "t"
                name = "app"
                version = "0.0.1"

                [dependencies]
                lib.workspace = true
                guava = { group = "com.google.guava", version = "^33" }
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(
                root.resolve("lib/jk.toml"), "group = \"t\"\nname = \"lib\"\nversion = \"0.0.1\"\njava = 17\n");
        Path out = root.resolve("target/app/guard/model.json");
        GuardModelSnapshot.write(root, List.of(root.resolve("app"), root.resolve("lib")), out);
        Object doc = MiniJson.parse(Files.readString(out));
        assertThat((List<Object>) MiniJson.get(doc, "modules")).containsExactly("", "app", "lib");
        Map<String, Object> deps = (Map<String, Object>) MiniJson.get(MiniJson.get(doc, "deps"), "app");
        List<Object> main = (List<Object>) Objects.requireNonNull(deps.get("dependencies"));
        assertThat(main).hasSize(2);
        assertThat(MiniJson.str(main.get(0), "coordinate")).isEqualTo("lib");
        assertThat(MiniJson.get(main.get(0), "workspace")).isEqualTo(Boolean.TRUE);
        assertThat(MiniJson.str(main.get(1), "coordinate")).isEqualTo("com.google.guava:guava");
        assertThat(MiniJson.str(main.get(1), "version")).isEqualTo("^33");
        List<Object> tiers = (List<Object>) MiniJson.get(MiniJson.get(doc, "tiers"), "tiers");
        assertThat(tiers).hasSize(2);
        assertThat(MiniJson.str(tiers.get(1), "name")).isEqualTo("jk test --profile slow");
        Map<String, Object> java = (Map<String, Object>) MiniJson.get(MiniJson.get(doc, "toolchain"), "java");
        assertThat(((Number) Objects.requireNonNull(java.get(""))).intValue()).isEqualTo(21);
        assertThat(((Number) Objects.requireNonNull(java.get("lib"))).intValue())
                .isEqualTo(17);
        assertThat((List<Object>) MiniJson.get(MiniJson.get(doc, "toolchain"), "repositories"))
                .containsExactly("corp");
        assertThat((List<Object>) MiniJson.get(doc, "lock")).isEmpty();
    }
}
