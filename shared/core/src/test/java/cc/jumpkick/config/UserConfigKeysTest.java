// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A key of {@code ~/.jk/config.toml} is unknown when no reader reads it, and the list of known keys
 * stays the readers' own: a table no list holds, a key declared twice, or a reader that reads a key
 * no table declares fails here.
 */
class UserConfigKeysTest {

    /** A table-valued read: {@code getTable("x")}, {@code TomlValues.optInt(t, "x")}, {@code t.get(List.of("x"))}. */
    private static final List<Pattern> SEGMENT_READS = List.of(
            Pattern.compile("getTable\\(\"([^\"]+)\"\\)"),
            Pattern.compile("TomlValues\\.opt\\w+\\(\\s*\\w+,\\s*\"([^\"]+)\""),
            Pattern.compile("\\.get\\(List\\.of\\(\"([^\"]+)\"\\)\\)"));

    /** A table's declaration: {@code static final UserConfigTable HTTP = UserConfigTable.of("http")}. */
    private static final Pattern TABLE_DECLARATION =
            Pattern.compile("static final UserConfigTable (\\w+)\\s*=\\s*UserConfigTable\\.(?:of|open)\\(");

    private static final String CORE = "shared/core/src/main/java";

    /** A dotted key literal, as a {@code TomlScan} read spells one. */
    private static final Pattern DOTTED = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");

    /** A parameter that carries {@code ~/.jk/config.toml}, by the readers' naming. */
    private static final Pattern USER_CONFIG_PARAM = Pattern.compile("Path userConfig\\b");

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

    @Tag("tree")
    @Test
    void every_key_a_reader_of_the_file_reads_is_known() throws IOException {
        List<String> unknown = new ArrayList<>();
        int readers = 0;
        for (Path main : mainSourceRoots(RepoRoot.find(UserConfigKeysTest.class))) {
            try (Stream<Path> walk = Files.walk(main)) {
                for (Path java :
                        walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(java);
                    if (!isReader(text)) continue;
                    readers++;
                    unknown.addAll(undeclaredReads(main.relativize(java).toString(), text));
                }
            }
        }
        assertThat(readers).as("the reader scan found too few files; it broke").isGreaterThan(20);
        assertThat(unknown)
                .as("keys read from ~/.jk/config.toml that no UserConfigTable declares")
                .isEmpty();
    }

    @Test
    void a_reader_of_an_undeclared_key_is_caught() {
        String reader = """
                TomlTable http = TomlValues.parse(userConfig).map(root -> root.getTable("http")).orElse(null);
                int port = TomlValues.optInt(http, "port").orElse(0);
                int typo = TomlValues.optInt(http, "prot").orElse(0);
                TomlTable retired = TomlValues.parse(userConfig).map(root -> root.getTable("telemetry")).orElse(null);
                static String level(Path userConfig) {
                    return TomlScan.scan(userConfig, "engine.log-levl").get("engine.log-levl");
                }
                """;

        assertThat(isReader(reader)).isTrue();
        assertThat(undeclaredReads("Fixture.java", reader))
                .containsExactlyInAnyOrder(
                        "Fixture.java: prot",
                        "Fixture.java: telemetry",
                        "Fixture.java: engine.log-levl",
                        "Fixture.java: engine.log-levl");
    }

    /** Whether {@code text} reads {@code ~/.jk/config.toml}, by the calls and names its readers use. */
    private static boolean isReader(String text) {
        return parsesTheFile(text) || scansTheFile(text);
    }

    /** TomlValues.parse and GlobalConfig.parseConfig read only {@code ~/.jk/config.toml}. */
    private static boolean parsesTheFile(String text) {
        return text.contains("TomlValues.parse(") || text.contains("parseConfig(");
    }

    /** A TomlScan read of the file names it {@code userConfig} and its keys dotted. */
    private static boolean scansTheFile(String text) {
        return text.contains("userConfigFile")
                || USER_CONFIG_PARAM.matcher(text).find();
    }

    /** The literal keys {@code text} reads that no table declares, each as {@code at: key}. */
    private static List<String> undeclaredReads(String at, String text) {
        Set<String> segments = new TreeSet<>();
        Set<String> tables = new TreeSet<>();
        Stream.concat(UserConfigKeys.EXACT.stream(), UserConfigKeys.OPEN.stream())
                .forEach(p -> {
                    segments.addAll(p);
                    tables.add(p.getFirst());
                });
        List<String> unknown = new ArrayList<>();
        for (Pattern read : parsesTheFile(text) ? SEGMENT_READS : List.<Pattern>of()) {
            Matcher m = read.matcher(text);
            while (m.find()) {
                if (!segments.contains(m.group(1))) unknown.add(at + ": " + m.group(1));
            }
        }
        Matcher d = DOTTED.matcher(scansTheFile(text) ? text : "");
        while (d.find()) {
            String key = d.group(1);
            List<String> path = Arrays.asList(key.split("\\."));
            if (tables.contains(path.getFirst()) && !UserConfigKeys.known(path)) unknown.add(at + ": " + key);
        }
        return unknown;
    }

    @Test
    void no_key_is_declared_twice() {
        List<List<String>> declared = UserConfigKeys.declared();
        Set<List<String>> seen = new HashSet<>();
        List<String> twice = new ArrayList<>();
        for (List<String> path : declared) {
            if (!seen.add(path)) twice.add(String.join(".", path));
        }
        assertThat(declared).as("declared keys; the derivation broke").hasSizeGreaterThan(60);
        assertThat(twice).as("keys two readers declare").isEmpty();
    }

    @Test
    void a_key_is_declared_once_per_table_and_an_open_table_declares_none() {
        UserConfigTable table = UserConfigTable.of("t");
        table.key("k");
        assertThatThrownBy(() -> table.key("k")).hasMessageContaining("t.k is declared twice");
        assertThatThrownBy(() -> UserConfigTable.open("o").key("k")).hasMessageContaining("open");
    }

    @Tag("tree")
    @Test
    void every_declared_table_is_listed() throws Exception {
        Path root = RepoRoot.find(UserConfigKeysTest.class);
        List<String> problems = new ArrayList<>();
        int declarations = 0;
        for (Path main : mainSourceRoots(root)) {
            try (Stream<Path> walk = Files.walk(main)) {
                for (Path java :
                        walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = TABLE_DECLARATION.matcher(Files.readString(java));
                    while (m.find()) {
                        declarations++;
                        String at = root.relativize(java) + ": " + m.group(1);
                        if (!main.equals(root.resolve(CORE))) {
                            problems.add(at + " is declared outside " + CORE + ", where UserConfigKeys cannot list it");
                            continue;
                        }
                        String type = main.relativize(java)
                                .toString()
                                .replace(java.getFileSystem().getSeparator(), ".")
                                .replaceFirst("\\.java$", "");
                        Field field = Class.forName(type).getDeclaredField(m.group(1));
                        field.setAccessible(true);
                        Object table = field.get(null);
                        if (UserConfigKeys.TABLES.stream().noneMatch(t -> t == table)) {
                            problems.add(at + " is not in UserConfigKeys.TABLES");
                        }
                    }
                }
            }
        }
        assertThat(declarations).as("table declarations found; the scan broke").isGreaterThan(20);
        assertThat(problems).isEmpty();
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
