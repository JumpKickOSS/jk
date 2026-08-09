// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewJkBuildRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * JK-1671: the scaffolded jk.toml is the client's base plus the plugin's fragments, and nothing
 * checked the result was valid TOML. `jk new --micronaut` shipped emitting two `[application]`
 * tables, so `jk lock` failed on the very next command. Render every framework scaffold and parse
 * what comes out.
 */
class ScaffoldTomlParsesTest {

    @ParameterizedTest(name = "--{0} ({1})")
    @CsvSource({
        "spring, java",
        "spring, kotlin",
        "grails, groovy",
        "quarkus, java",
        "quarkus, kotlin",
        "micronaut, java",
        "micronaut, kotlin",
    })
    void a_scaffolded_jk_toml_parses(String flag, String lang, @TempDir Path dir) throws Exception {
        String toml = scaffoldToml(dir, flag, lang);

        Path file = Files.writeString(dir.resolve("jk.toml"), toml);
        // The failure this guards is a duplicate table, which the parser rejects outright.
        assertThat(JkBuildParser.parse(file).project().name()).isEqualTo("svc");
        assertThat(countTables(toml, "application"))
                .as("exactly one owner for [application]:%n%s", toml)
                .isLessThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "--micronaut ({0}) is a runnable fat jar")
    @CsvSource({"java", "kotlin"})
    void a_micronaut_scaffold_is_a_runnable_fat_jar(String lang, @TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("jk.toml"), scaffoldToml(dir, "micronaut", lang));

        var build = JkBuildParser.parse(file);
        assertThat(build.assembly()).isTrue();
        assertThat(build.mainClass()).isNotNull().contains("Application");
    }

    private static String scaffoldToml(Path dir, String flag, String lang) {
        NewInputs inputs = inputs(dir, flag, lang);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("plugin", inputs.frameworkPluginFlag());
        params.put("lang", lang);
        params.put("package", inputs.group());
        params.put("group", inputs.group());
        params.put("name", inputs.name());
        params.put("simpleLayout", "false");
        params.put("sample", "false"); // only jk.toml is under test
        params.put("quarkus.version", cc.jumpkick.model.ToolDefaults.QUARKUS_PLATFORM_FLOOR);
        params.put("baseToml", NewJkBuildRenderer.render(inputs));

        var generated = ScaffoldOps.scaffold(dir, params);
        assertThat(generated.error()).isNull();
        int index = generated.paths().indexOf(dir.resolve("jk.toml").toString());
        assertThat(index).as("scaffold must produce a jk.toml").isNotNegative();
        return generated.contents().get(index);
    }

    /** Mirrors {@code NewCommand.fromFlags} for the fields that reach the renderer. */
    private static NewInputs inputs(Path dir, String flag, String lang) {
        var language =
                switch (lang) {
                    case "kotlin" -> NewInputs.Language.KOTLIN;
                    case "groovy" -> NewInputs.Language.GROOVY;
                    default -> NewInputs.Language.JAVA;
                };
        String mainClass = "com.example." + (language == NewInputs.Language.KOTLIN ? "ApplicationKt" : "Application");
        return new NewInputs(
                "com.example",
                "svc",
                "25",
                25,
                25,
                Optional.empty(),
                Optional.of(mainClass),
                false, // assembly: NewInputs forces it on where the framework requires it
                false,
                flag.equals("spring"),
                flag.equals("grails"),
                flag.equals("quarkus"),
                flag.equals("micronaut"),
                false,
                language,
                "traditional",
                Optional.empty(),
                List.of(),
                false,
                dir);
    }

    private static int countTables(String toml, String table) {
        int count = 0;
        for (String line : toml.split("\n")) {
            if (line.strip().equals("[" + table + "]")) count++;
        }
        return count;
    }
}
