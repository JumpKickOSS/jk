// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A key of {@code ~/.jk/config.toml} is unknown when no reader reads it, and the list of known keys
 * stays the readers' own: a reader that reads a key the list lacks fails here.
 */
class UserConfigKeysTest {

    /** A table-valued read: {@code getTable("x")}, {@code TomlValues.optInt(t, "x")}, {@code t.get(List.of("x"))}. */
    private static final List<Pattern> SEGMENT_READS = List.of(
            Pattern.compile("getTable\\(\"([^\"]+)\"\\)"),
            Pattern.compile("TomlValues\\.opt\\w+\\(\\s*\\w+,\\s*\"([^\"]+)\""),
            Pattern.compile("\\.get\\(List\\.of\\(\"([^\"]+)\"\\)\\)"));

    /** GlobalConfig's own scalar reads: {@code stringFromRoot(file, "k")}, {@code stringFromGlobal(file, "t", "k")}. */
    private static final Pattern GLOBAL_READ =
            Pattern.compile("stringFrom(?:Root|Global)\\(\\s*\\w+,\\s*\"([^\"]+)\"(?:,\\s*\"([^\"]+)\")?\\)");

    /** A dotted key literal, as a {@code TomlScan} read spells one. */
    private static final Pattern DOTTED = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");

    /** A parameter that carries {@code ~/.jk/config.toml}, by the readers' naming. */
    private static final Pattern USER_CONFIG_PARAM = Pattern.compile("Path userConfig\\b");

    /** Dotted literals in reader files that are not keys of the file. */
    private static final Set<String> NOT_KEYS = Set.of("config.parse-error");

    @Test
    void a_file_of_known_keys_has_no_unknown_key(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("config.toml"), """
                nerd-font = "auto"
                self-update = "auto"
                [config]
                color = "never"
                [engine]
                max-heap-mb = 512
                [cache]
                remote = "https://cache.test"
                [repositories.corp]
                url = "https://repo.test/"
                mirror-of = ["*"]
                [mirrors]
                node = "https://mirror.test/node/"
                [node.scopes]
                "@acme" = "https://npm.test/"
                [image]
                base = "eclipse-temurin:25-jre"
                env = { LANG = "C.UTF-8" }
                [templates.sources]
                corp = { url = "https://git.test/t.git", rev = "main" }
                [[forge.host]]
                name = "git.test"
                client-id = "abc"
                """);

        assertThat(UserConfigKeys.unknown(file)).isEmpty();
    }

    @Test
    void misspelt_retired_and_misplaced_keys_are_unknown_in_file_order(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("config.toml"), """
                nerd-fonts = true
                [engine]
                max-heap-mb = 512
                heap-mb = 512
                [cache.remote]
                url = "https://cache.test"
                [retired]
                key = 1
                """);

        assertThat(UserConfigKeys.unknown(file))
                .containsExactly("nerd-fonts", "engine.heap-mb", "cache.remote.url", "retired.key");
    }

    @Test
    void an_absent_or_broken_file_has_no_unknown_key(@TempDir Path tmp) throws IOException {
        assertThat(UserConfigKeys.unknown(tmp.resolve("absent.toml"))).isEmpty();
        Path broken = Files.writeString(tmp.resolve("broken.toml"), "[oops\nnot-a-key = 1\n");
        assertThat(UserConfigKeys.unknown(broken)).isEmpty();
    }

    @Test
    void each_unknown_key_is_first_seen_once_and_a_new_one_after_it(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("config.toml"), "[engine]\nheap-mb = 1\n");
        Path state = tmp.resolve("state");

        assertThat(UserConfigKeys.firstSeen(file, state)).containsExactly("engine.heap-mb");
        assertThat(UserConfigKeys.firstSeen(file, state)).isEmpty();

        Files.writeString(file, "[engine]\nheap-mb = 1\nheap = 2\n");
        assertThat(UserConfigKeys.firstSeen(file, state)).containsExactly("engine.heap");
    }

    @Test
    void a_state_dir_that_cannot_be_written_stays_quiet(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("config.toml"), "unknown = 1\n");
        Path notADir = Files.writeString(tmp.resolve("state"), "a file");

        assertThat(UserConfigKeys.firstSeen(file, notADir)).isEmpty();
    }

    @Test
    void the_notice_is_one_short_line_naming_key_and_file() {
        Path file = Path.of("/home/u/.jk/config.toml");
        String line = "jk: " + UserConfigKeys.notice("engine.heap-mb", file);
        assertThat(line).isEqualTo("jk: engine.heap-mb: unknown key in " + file + ", ignored");
        assertThat(line.length()).isLessThanOrEqualTo(78);
    }

    @Test
    void the_dashboard_lists_unknown_keys_first(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("config.toml"), "[engine]\nheap-mb = 1\n");

        var rows = EffectiveUserConfig.rows(file, k -> null);

        assertThat(rows.getFirst().key()).isEqualTo("engine.heap-mb");
        assertThat(rows.getFirst().effectiveValue()).isEqualTo(EffectiveUserConfig.UNKNOWN);
        assertThat(rows.getFirst().overridden()).isTrue();
    }

    @Test
    void every_key_the_dashboard_shows_is_known() {
        for (var row : EffectiveUserConfig.rows(Path.of("/no/such/config.toml"), k -> null)) {
            assertThat(UserConfigKeys.known(Arrays.asList(row.key().split("\\."))))
                    .as("dashboard row %s", row.key())
                    .isTrue();
        }
    }

    @Test
    void every_key_a_reader_of_the_file_reads_is_known() throws IOException {
        Set<String> segments = new TreeSet<>();
        Set<String> tables = new TreeSet<>();
        Stream.concat(UserConfigKeys.EXACT.stream(), UserConfigKeys.OPEN.stream())
                .forEach(p -> {
                    segments.addAll(p);
                    tables.add(p.getFirst());
                });
        List<String> unknown = new ArrayList<>();
        Set<Path> readers = new LinkedHashSet<>();
        for (Path main : mainSourceRoots(RepoRoot.find(UserConfigKeysTest.class))) {
            try (Stream<Path> walk = Files.walk(main)) {
                for (Path java :
                        walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(java);
                    String at = main.relativize(java).toString();
                    // TomlValues.parse and GlobalConfig.parseConfig read only ~/.jk/config.toml.
                    if (text.contains("TomlValues.parse(") || text.contains("parseConfig(")) {
                        readers.add(java);
                        for (Pattern read : SEGMENT_READS) {
                            Matcher m = read.matcher(text);
                            while (m.find()) {
                                if (!segments.contains(m.group(1))) unknown.add(at + ": " + m.group(1));
                            }
                        }
                        Matcher g = GLOBAL_READ.matcher(text);
                        while (g.find()) {
                            List<String> path = g.group(2) == null
                                    ? Arrays.asList(g.group(1).split("\\."))
                                    : List.of(g.group(1), g.group(2));
                            if (!UserConfigKeys.known(path)) unknown.add(at + ": " + String.join(".", path));
                        }
                    }
                    // A TomlScan read of the file names its keys dotted.
                    if (text.contains("userConfigFile")
                            || USER_CONFIG_PARAM.matcher(text).find()) {
                        readers.add(java);
                        Matcher d = DOTTED.matcher(text);
                        while (d.find()) {
                            String key = d.group(1);
                            List<String> path = Arrays.asList(key.split("\\."));
                            if (tables.contains(path.getFirst())
                                    && !NOT_KEYS.contains(key)
                                    && !UserConfigKeys.known(path)) {
                                unknown.add(at + ": " + key);
                            }
                        }
                    }
                }
            }
        }
        assertThat(readers).as("the reader scan found too few files; it broke").hasSizeGreaterThan(20);
        assertThat(unknown)
                .as("keys read from ~/.jk/config.toml that UserConfigKeys lacks")
                .isEmpty();
    }

    /** Every module's production sources. */
    private static List<Path> mainSourceRoots(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String family : List.of("shared", "server", "clients", "plugins")) {
            try (var modules = Files.list(root.resolve(family))) {
                for (Path module : modules.sorted().toList()) {
                    Path main = module.resolve("src/main/java");
                    if (Files.isDirectory(main)) out.add(main);
                }
            }
        }
        if (out.size() < 20) throw new AssertionError("expected every module's src/main/java, found " + out.size());
        return out;
    }
}
