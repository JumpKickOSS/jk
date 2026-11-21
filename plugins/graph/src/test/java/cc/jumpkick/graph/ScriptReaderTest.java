// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.GraphCacheKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptReaderTest {

    @TempDir
    Path tmp;

    @Test
    void upgradingOxcParserMissesTheCachedScriptFragments() throws IOException {
        Path oxc = install("0.90.0", "11.8.0");
        String before =
                GraphCacheKeys.declarations("src/app.ts", "abc", reader(oxc).flavor("src/app.ts"));
        install("0.91.0", "11.8.0");
        String after =
                GraphCacheKeys.declarations("src/app.ts", "abc", reader(oxc).flavor("src/app.ts"));
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void upgradingOxcResolverMissesTheCachedScriptFragments() throws IOException {
        Path oxc = install("0.90.0", "11.8.0");
        String before = reader(oxc).flavor("src/app.ts");
        install("0.90.0", "11.9.0");
        assertThat(reader(oxc).flavor("src/app.ts")).isNotEqualTo(before);
    }

    @Test
    void theSameInstallKeepsItsFlavorAndScannedFilesHaveNone() throws IOException {
        Path oxc = install("0.90.0", "11.8.0");
        assertThat(reader(oxc).flavor("src/app.ts"))
                .isEqualTo(reader(oxc).flavor("src/app.ts"))
                .startsWith("+oxc");
        assertThat(reader(oxc).flavor("src/App.vue")).isEmpty();
        assertThat(new ScriptReader(null, null).flavor("src/app.ts")).isEmpty();
    }

    private ScriptReader reader(Path oxc) {
        return new ScriptReader(tmp.resolve("node"), oxc);
    }

    private Path install(String parser, String resolver) throws IOException {
        Path oxc = tmp.resolve("oxc");
        writePackage(oxc, "oxc-parser", parser);
        writePackage(oxc, "oxc-resolver", resolver);
        return oxc;
    }

    private static void writePackage(Path oxc, String name, String version) throws IOException {
        Path dir = Files.createDirectories(oxc.resolve("node_modules").resolve(name));
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}");
    }
}
