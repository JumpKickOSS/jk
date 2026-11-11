// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace member's module stem reads more than its own directory: the root manifest and lock,
 * and the classes of the siblings it compiles against. Its action key and its dirty-memo token both
 * move with each.
 */
class BuildLogicMemberKeysTest {

    @Test
    void a_member_stem_key_moves_with_the_root_manifest_the_root_lock_and_a_sibling_output(@TempDir Path root)
            throws Exception {
        Path app = scaffold(root);
        Path libClass =
                Files.createDirectories(root.resolve("lib/target/classes/lib")).resolve("Lib.class");
        Files.writeString(libClass, "v1");

        List<String> base = BuildLogicSupport.projectInputTokens(app);
        assertThat(base).anyMatch(t -> t.startsWith("sibling:lib/target/classes/lib/Lib.class:"));
        assertEquals(base, BuildLogicSupport.projectInputTokens(app), "stable while nothing moves");

        Files.writeString(root.resolve("jk-lock.toml"), "version = 1\n# moved\n");
        List<String> afterLock = BuildLogicSupport.projectInputTokens(app);
        assertNotEquals(base, afterLock, "the root lock moves the key");

        Files.writeString(root.resolve("jk.toml"), Files.readString(root.resolve("jk.toml")) + "\n# moved\n");
        List<String> afterManifest = BuildLogicSupport.projectInputTokens(app);
        assertNotEquals(afterLock, afterManifest, "the root manifest moves the key");

        Files.writeString(libClass, "v2");
        assertNotEquals(afterManifest, BuildLogicSupport.projectInputTokens(app), "a sibling's classes move the key");
    }

    @Test
    void a_member_stem_memo_token_moves_with_the_root_manifest_the_root_lock_and_a_sibling_row(@TempDir Path root)
            throws Exception {
        Path app = scaffold(root).toAbsolutePath().normalize();
        Path lib = root.resolve("lib").toAbsolutePath().normalize();
        Path ws = root.toAbsolutePath().normalize();
        JkBuild manifest = JkBuildParser.parse(app.resolve("jk.toml"));

        String base = MemoInputs.logicToken(app, ws, manifest, Map.of(lib, "fp1"));
        assertEquals(base, MemoInputs.logicToken(app, ws, manifest, Map.of(lib, "fp1")));
        assertNotEquals(base, MemoInputs.logicToken(app, ws, manifest, Map.of(lib, "fp2")), "a sibling row");

        Files.writeString(root.resolve("jk-lock.toml"), "version = 1\n# moved\n");
        String afterLock = MemoInputs.logicToken(app, ws, manifest, Map.of(lib, "fp1"));
        assertNotEquals(base, afterLock, "the root lock");

        Files.writeString(root.resolve("jk.toml"), Files.readString(root.resolve("jk.toml")) + "\n# moved\n");
        assertNotEquals(afterLock, MemoInputs.logicToken(app, ws, manifest, Map.of(lib, "fp1")), "the root manifest");
    }

    /** A root with {@code lib} and {@code app}, {@code app} compiling against {@code lib} and carrying a module stem. */
    private static Path scaffold(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.writeString(root.resolve("jk-lock.toml"), "version = 1\n");
        module(root, "lib", "");
        Path app = module(root, "app", """
                [dependencies]
                lib = { workspace = true }
                """);
        Files.createDirectories(app.resolve(".jk/scripts"));
        Files.writeString(app.resolve(".jk/scripts/after-resources.groovy"), "// reads the root\n");
        return app;
    }

    private static Path module(Path root, String name, String deps) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"

                %s""".formatted(name, deps));
        return dir;
    }
}
