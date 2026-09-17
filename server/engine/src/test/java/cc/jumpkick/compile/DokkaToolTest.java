// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.base.DokkaResolver;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Dokka configuration file the step hands the CLI: one JVM source set over the module's
 * source files with the compile classpath, the format's plugin classpath, offline, no stdlib or
 * JDK links, so the run reads nothing but its inputs.
 */
class DokkaToolTest {

    @Test
    void the_configuration_names_the_sources_the_classpath_and_the_plugins() {
        // Dokka is handed absolute paths, so the expectations render the same Paths the call was
        // given rather than POSIX literals — "/cas/x.jar" is not absolute on Windows and comes back
        // as "C:\cas\x.jar".
        Path plugin = Path.of("/cas/javadoc-plugin.jar");
        Path greeter = Path.of("/ws/lib/src/main/kotlin/Greeter.kt");
        Path util = Path.of("/ws/lib/src/main/java/Util.java");
        Path stdlib = Path.of("/cas/kotlin-stdlib.jar");
        DokkaResolver.Tool tool = new DokkaResolver.Tool("2.2.0", Path.of("/cas/dokka-cli.jar"), List.of(plugin));
        String json = DokkaTool.configJson(
                tool, Path.of("/ws/lib/target/javadoc"), "lib", "1.0.0", List.of(greeter, util), List.of(stdlib), 25);

        Object parsed = MiniJson.parse(json);
        assertThat(MiniJson.str(parsed, "moduleName")).isEqualTo("lib");
        assertThat(MiniJson.str(parsed, "moduleVersion")).isEqualTo("1.0.0");
        assertThat(MiniJson.str(parsed, "outputDir")).endsWith("javadoc");
        assertThat(MiniJson.get(parsed, "offlineMode")).isEqualTo(Boolean.TRUE);
        assertThat(MiniJson.list(parsed, "pluginsClasspath")).isEqualTo(List.of(abs(plugin)));
        Object sourceSet = MiniJson.list(parsed, "sourceSets").get(0);
        assertThat(MiniJson.list(sourceSet, "sourceRoots")).isEqualTo(List.of(abs(greeter), abs(util)));
        assertThat(MiniJson.list(sourceSet, "classpath")).isEqualTo(List.of(abs(stdlib)));
        assertThat(MiniJson.str(sourceSet, "analysisPlatform")).isEqualTo("jvm");
        assertThat(MiniJson.get(sourceSet, "jdkVersion")).isEqualTo(25.0);
        assertThat(MiniJson.get(sourceSet, "noJdkLink")).isEqualTo(Boolean.TRUE);
        assertThat(MiniJson.get(sourceSet, "noStdlibLink")).isEqualTo(Boolean.TRUE);
        assertThat(MiniJson.str(MiniJson.get(sourceSet, "sourceSetID"), "scopeId"))
                .isEqualTo("lib");
    }

    /** A path as Dokka is handed it: absolute, in the host's own spelling. */
    private static String abs(Path p) {
        return p.toAbsolutePath().toString();
    }
}
