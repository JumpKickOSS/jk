// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fat jar embeds the module's SBOM, so the SBOM is part of its key: a lock change that leaves
 * the module's bundled jars alone must not restore an {@code -all.jar} carrying the old SBOM.
 */
class AssemblySbomKeyTest {

    private static final JkBuild APP =
            JkBuild.builder(new Project("com.example", "app", "1.0.0", 25)).build();

    @Test
    void a_different_sbom_is_a_different_assembly_key(@TempDir Path module) {
        String before = key(module, "{\"components\":[\"a:1\"]}".getBytes(StandardCharsets.UTF_8));
        String after = key(module, "{\"components\":[\"a:1\",\"b:2\"]}".getBytes(StandardCharsets.UTF_8));
        String none = key(module, null);

        assertThat(before).isNotEqualTo(after).isNotEqualTo(none);
        assertThat(key(module, "{\"components\":[\"a:1\"]}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(before);
    }

    /** A member's jar names the rows its own runtime closure reaches, not every member's. */
    @Test
    void a_members_sbom_names_its_closure_and_not_its_siblings(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["core", "other", "app"]
                """);
        module(root, "core", """
                [dependencies]
                riding = { group = "com.foo", name = "riding", version = "1.0" }
                """);
        module(root, "other", """
                [dependencies]
                elsewhere = { group = "com.foo", name = "elsewhere", version = "1.0" }
                """);
        Path app = module(root, "app", """
                [dependencies]
                core = { workspace = true }
                own = { group = "com.foo", name = "own", version = "1.0" }
                """);
        Path lockFile = root.resolve("jk-lock.toml");
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(row("com.foo:riding"), row("com.foo:elsewhere"), row("com.foo:own"))),
                lockFile);

        byte[] sbom = PackagingKeys.assemblySbom(JkBuildParser.parse(app.resolve("jk.toml")), app, lockFile);

        assertThat(new String(sbom, StandardCharsets.UTF_8))
                .contains("pkg:maven/com.foo/own@1.0", "pkg:maven/com.foo/riding@1.0")
                .doesNotContain("elsewhere");
    }

    private static Path module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "1.0"

                %s""".formatted(name, tables));
        return dir;
    }

    private static Lockfile.Artifact row(String module) {
        return new Lockfile.Artifact(
                module + ":jar:",
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:abcd",
                null,
                List.of(Scope.MAIN),
                List.of());
    }

    private static String key(Path module, byte @Nullable [] sbom) {
        return PackagingKeys.assembly(
                        module.resolve("target/app-all.jar"), module, APP, Map.of(), "classes", "contrib", "deps", sbom)
                .key();
    }
}
