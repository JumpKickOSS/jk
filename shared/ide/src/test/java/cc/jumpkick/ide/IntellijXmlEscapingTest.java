// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every attribute the IntelliJ generator emits is XML-escaped. Directory names are the user's,
 * and one holding {@code &} or a quote turned a project file into one the IDE refuses to parse.
 */
class IntellijXmlEscapingTest {

    private static IdeModule module(Path dir, String name, String target) {
        Path t = dir.resolve(target);
        return new IdeModule(
                name,
                25,
                null,
                t.resolve("classes"),
                t.resolve("test-classes"),
                t.resolve("jdt/classes/main"),
                t.resolve("jdt/classes/test"),
                t.resolve("generated-sources/annotations"),
                t.resolve("generated-sources/annotations-test"));
    }

    @Test
    // The null SDKs are deliberate: modules that declare none.
    @SuppressWarnings("NullAway")
    void output_directories_and_library_scopes_are_escaped_in_the_iml(@TempDir Path ws) {
        Path appDir = ws.resolve("app");
        IdeModule app = module(appDir, "app", "out&\"put");
        Map<Path, IdeModule> all = new LinkedHashMap<>();
        all.put(appDir, app);

        String iml = IntellijIdeGenerator.imlXml(appDir, app, List.of(), List.of(), all, null, null, List.of());

        assertThat(iml).contains("<output url=\"file://$MODULE_DIR$/out&amp;&quot;put/classes\" />");
        assertThat(iml).contains("<output-test url=\"file://$MODULE_DIR$/out&amp;&quot;put/test-classes\" />");
        assertThat(iml).doesNotContain("out&\"put");
    }

    @Test
    void library_jar_urls_are_escaped(@TempDir Path tmp) {
        Path jar = tmp.resolve("repo&\"cache").resolve("lib-1.0.jar");
        Path sources = tmp.resolve("repo&\"cache").resolve("lib-1.0-sources.jar");

        String xml = IntellijIdeGenerator.libraryXml(new LibDef("lib", "lib", jar, sources));

        assertThat(xml).contains("repo&amp;&quot;cache/lib-1.0.jar!/");
        assertThat(xml).contains("repo&amp;&quot;cache/lib-1.0-sources.jar!/");
        assertThat(xml).doesNotContain("repo&\"cache");
    }
}
