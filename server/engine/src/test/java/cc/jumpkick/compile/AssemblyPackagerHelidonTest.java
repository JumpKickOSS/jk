// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fat jar's merge of Helidon's shared-path {@code META-INF/helidon} files. Fixture contents are
 * cut down from the helidon-webserver and helidon-config 27.0.1 jars; each case puts the file in two
 * jars and reads back the one merged entry.
 */
class AssemblyPackagerHelidonTest {

    @Test
    void manifest_keeps_one_header_and_every_jars_paths_deduplicated_first_seen_first(@TempDir Path tmp)
            throws IOException {
        Path webserver = jar(
                tmp,
                "helidon-webserver.jar",
                "META-INF/helidon/manifest",
                "#HELIDON MANIFEST#\n"
                        + "META-INF/helidon/io.helidon.webserver/service-registry.json\n"
                        + "META-INF/helidon/io.helidon.webserver/feature-registry.json\n"
                        + "META-INF/helidon/shared/service.loader\n");
        Path config = jar(
                tmp,
                "helidon-config.jar",
                "META-INF/helidon/manifest",
                "#HELIDON MANIFEST#\r\n"
                        + "META-INF/helidon/io.helidon.config/service.loader\r\n"
                        + "\r\n"
                        + "META-INF/helidon/shared/service.loader\r\n"
                        + "META-INF/helidon/io.helidon.config/service-registry.json");

        String manifest = mergedEntry(tmp, "META-INF/helidon/manifest", webserver, config);

        assertThat(manifest)
                .isEqualTo("#HELIDON MANIFEST#\n"
                        + "META-INF/helidon/unnamed/app/service-registry.json\n"
                        + "META-INF/helidon/io.helidon.webserver/service-registry.json\n"
                        + "META-INF/helidon/io.helidon.webserver/feature-registry.json\n"
                        + "META-INF/helidon/shared/service.loader\n"
                        + "META-INF/helidon/io.helidon.config/service.loader\n"
                        + "META-INF/helidon/io.helidon.config/service-registry.json\n");
    }

    @Test
    void config_metadata_is_one_json_array_of_every_jars_modules(@TempDir Path tmp) throws IOException {
        Path webserver = jar(
                tmp,
                "helidon-webserver.jar",
                "META-INF/helidon/config-metadata.json",
                "[{\"module\":\"io.helidon.webserver\",\"types\":[{\"type\":\"io.helidon.webserver.ErrorHandling\","
                        + "\"options\":[{\"key\":\"include-entity\",\"defaultValue\":\"false\"}]}]}]");
        Path security = jar(
                tmp,
                "helidon-security.jar",
                "META-INF/helidon/config-metadata.json",
                "\n[\n  {\"module\":\"io.helidon.security\",\"types\":[]},\n"
                        + "  {\"module\":\"io.helidon.security.providers\",\"types\":[]}\n]\n");
        Path empty = jar(tmp, "empty.jar", "META-INF/helidon/config-metadata.json", "[ ]");

        String merged = mergedEntry(tmp, "META-INF/helidon/config-metadata.json", webserver, empty, security);

        List<?> modules = jsonArray(merged);
        assertThat(modules)
                .extracting(m -> MiniJson.str(m, "module"))
                .containsExactly("io.helidon.webserver", "io.helidon.security", "io.helidon.security.providers");
        assertThat(MiniJson.list(modules.get(0), "types")).hasSize(1);
    }

    @Test
    void top_level_feature_and_service_registries_concatenate_as_json_arrays(@TempDir Path tmp) throws IOException {
        // Helidon 4 keeps these at META-INF/helidon/ in every jar; 27 moves them under a module directory.
        Path common = jarOf(
                tmp,
                "helidon-common.jar",
                Map.of(
                        "META-INF/helidon/feature-registry.json",
                                "[{\"module\":\"io.helidon.common\",\"name\":\"Common\",\"flavor\":[\"SE\"]}]",
                        "META-INF/helidon/service-registry.json", "[{\"module\":\"io.helidon.common\"}]"));
        Path webserver = jarOf(
                tmp,
                "helidon-webserver.jar",
                Map.of(
                        "META-INF/helidon/feature-registry.json",
                                "[{\"module\":\"io.helidon.webserver\",\"name\":\"WebServer\",\"flavor\":[\"SE\"]}]",
                        "META-INF/helidon/service-registry.json", "[{\"module\":\"io.helidon.webserver\"}]"));

        for (String name :
                List.of("META-INF/helidon/feature-registry.json", "META-INF/helidon/service-registry.json")) {
            List<?> merged = jsonArray(mergedEntry(tmp, name, common, webserver));
            assertThat(merged)
                    .as(name)
                    .extracting(m -> MiniJson.str(m, "module"))
                    .containsExactly("io.helidon.common", "io.helidon.webserver");
        }
    }

    @Test
    void service_loader_concatenates_lines_and_drops_repeats(@TempDir Path tmp) throws IOException {
        Path config = jar(
                tmp,
                "helidon-config.jar",
                "META-INF/helidon/service.loader",
                "# List of service contracts\n"
                        + "io.helidon.config.spi.ConfigParser\n"
                        + "io.helidon.config.spi.ConfigFilter\n");
        Path webserver = jar(
                tmp,
                "helidon-webserver.jar",
                "META-INF/helidon/service.loader",
                "# List of service contracts\n"
                        + "io.helidon.webserver.spi.ServerFeatureProvider\n"
                        + "io.helidon.config.spi.ConfigParser");

        assertThat(mergedEntry(tmp, "META-INF/helidon/service.loader", config, webserver))
                .isEqualTo("# List of service contracts\n"
                        + "io.helidon.config.spi.ConfigParser\n"
                        + "io.helidon.config.spi.ConfigFilter\n"
                        + "io.helidon.webserver.spi.ServerFeatureProvider\n");
    }

    @Test
    void serial_config_unions_patterns_and_keeps_the_reject_all_last(@TempDir Path tmp) throws IOException {
        Path common = jar(
                tmp,
                "helidon-common.jar",
                "META-INF/helidon/serial-config.properties",
                "pattern=io.helidon.common.**;java.util.**;!*\n");
        Path webserver = jar(
                tmp,
                "helidon-webserver.jar",
                "META-INF/helidon/serial-config.properties",
                "# webserver\npattern=io.helidon.webserver.**;\\\n  java.util.**\n");

        String merged = mergedEntry(tmp, "META-INF/helidon/serial-config.properties", common, webserver);

        Properties loaded = new Properties();
        loaded.load(new StringReader(merged));
        assertThat(loaded.stringPropertyNames()).containsExactly("pattern");
        assertThat(loaded.getProperty("pattern"))
                .isEqualTo("io.helidon.common.**;java.util.**;io.helidon.webserver.**;!*");
    }

    @Test
    void per_module_registries_are_distinct_paths_and_kept_whole(@TempDir Path tmp) throws IOException {
        String registry = "[{\"module\":\"io.helidon.webserver\"}]";
        Path webserver = jar(
                tmp, "helidon-webserver.jar", "META-INF/helidon/io.helidon.webserver/service-registry.json", registry);

        assertThat(mergedEntry(tmp, "META-INF/helidon/io.helidon.webserver/service-registry.json", webserver))
                .isEqualTo(registry);
    }

    /**
     * Assembles the project (which carries its own generated manifest, as a Helidon inject app does)
     * with {@code deps} in order and returns the one entry {@code name} of the fat jar.
     */
    private static String mergedEntry(Path tmp, String name, Path... deps) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("META-INF/helidon"));
        Files.writeString(
                classes.resolve("META-INF/helidon/manifest"),
                "#HELIDON MANIFEST#\nMETA-INF/helidon/unnamed/app/service-registry.json\n");
        Path out = tmp.resolve("app-all.jar");
        new AssemblyPackager()
                .packageAssembly(
                        new AssemblyPackager.AssemblyRequest(classes, List.of(deps), out, "app.Main", Map.of(), 0L));
        try (JarFile jf = new JarFile(out.toFile())) {
            JarEntry entry = jf.getJarEntry(name);
            assertThat(entry).as(name).isNotNull();
            return new String(jf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Parses {@code json} strictly and asserts it is one array. */
    private static List<?> jsonArray(String json) {
        Object parsed = MiniJson.parse(json);
        assertThat(parsed).isInstanceOf(List.class);
        return (List<?>) Objects.requireNonNull(parsed);
    }

    private static Path jar(Path tmp, String fileName, String entry, String content) throws IOException {
        return jarOf(tmp, fileName, Map.of(entry, content));
    }

    private static Path jarOf(Path tmp, String fileName, Map<String, String> entries) throws IOException {
        Path jar = tmp.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
