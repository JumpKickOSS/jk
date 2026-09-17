// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.Scope;
import cc.jumpkick.testing.RepoRoot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lock's row grammar reads straight into tables, and reads exactly what tomlj reads: every
 * lock the writer renders, every lock in this checkout, and any corpus lock named by {@code
 * JK_LOCK_CORPUS} (a path-separator list) parse to equal lockfiles through both, without the row
 * reader handing off. Text outside the grammar hands off to tomlj, whose diagnostics stand.
 */
class LockRowParserTest {

    private static final Lockfile.Artifact.GitInfo GIT =
            new Lockfile.Artifact.GitInfo("https://github.com/acme/widgets", "3f2a9c1b4d5e6f70", "tag:v1.4.0");

    /** One lock that exercises every field the writer emits, in every table it emits. */
    private static Lockfile everyField() {
        Lockfile.Artifact rich = new Lockfile.Artifact(
                "org.acme:widgets:jar:",
                "1.4.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08",
                "libs/widgets \"quoted\" \\ path.jar",
                List.of(Scope.MAIN, Scope.TEST),
                List.of("org.acme:core@1.0", "org.acme:util@2.0"),
                "platform:org.acme:bom",
                GIT,
                "sha256:e5e31b195fcc7e9c5da4b191af3b0082ee616c13f61cbd79409ffa65e4dd5c47",
                Map.of("org.acme:core@1.0", "^1.0"),
                List.of("org.acme:legacy <- jk.toml:widgets"),
                List.of("apps/one", "apps/two"));
        Lockfile.Artifact bare = new Lockfile.Artifact(
                "org.acme:core:jar:", "1.0", "central", null, null, List.of(Scope.MAIN), List.of(), null, null, null);
        JdkPin jdk = new JdkPin("temurin", "25.0.4.1", "", "");
        GraalPin graal = new GraalPin("", "", "graalvm", "25");
        ModuleEntry module = new ModuleEntry(
                "apps/one",
                "org.acme",
                "one",
                "0.1.0",
                21,
                "2.4.10",
                "4.0.0",
                "3.7.0",
                "The one app — tabs\tand \"quotes\"",
                "src/main/java",
                Boolean.FALSE,
                Boolean.FALSE);
        ModuleEntry plain =
                new ModuleEntry("apps/two", "org.acme", "two", "0.1.0", null, null, null, null, null, null, null, null);
        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.13.7",
                Lockfile.RESOLUTION_ALGORITHM,
                jdk,
                graal,
                "2.4.10",
                "3.7.0",
                List.of(rich, bare),
                List.of(
                        new Lockfile.PluginEntry("cc.jumpkick:jk-plugin-spring-boot", "0.13.7", "sha256:abcd"),
                        new Lockfile.PluginEntry("org.acme:local-plugin", "0.1.0", null, "plugins/local")),
                List.of(new Lockfile.SdkEntry("platforms;android-35", "2")),
                List.of(module, plain),
                "0.12.0",
                "ca15c7a42c4048bd5320cd9280fcd1734c58322e432462c0a56c56ebb4bdd2db",
                "6cae4ae05a1df8b09b0db36dad722df4",
                new Lockfile.NativeMetadata("0.3.16", "sha256:0123"),
                null);
    }

    @Test
    void every_field_the_writer_emits_reads_the_same_through_both_grammars() {
        String text = LockfileWriter.render(everyField());
        Lockfile rows = LockfileReader.readRows(text, "<test>");
        Lockfile toml = LockfileReader.readToml(text, "<test>");
        assertThat(rows).isEqualTo(toml);
        assertThat(LockfileWriter.render(rows)).isEqualTo(text);
        Lockfile.Artifact widgets = rows.artifacts().stream()
                .filter(a -> a.name().startsWith("org.acme:widgets"))
                .findFirst()
                .orElseThrow();
        assertThat(widgets.path()).isEqualTo("libs/widgets \"quoted\" \\ path.jar");
        assertThat(widgets.declared()).containsEntry("org.acme:core@1.0", "^1.0");
        assertThat(widgets.members()).containsExactly("apps/one", "apps/two");
        assertThat(rows.modules().getFirst().java()).isEqualTo(21);
        assertThat(rows.modules().getFirst().m2integration()).isFalse();
        assertThat(rows.nativeMetadata()).isEqualTo(new Lockfile.NativeMetadata("0.3.16", "sha256:0123"));
    }

    @Test
    void the_row_grammar_decodes_every_escape_the_writer_and_toml_spell() {
        String text = """
                version = 1
                generated-by = "jk \\u0074\\U00000065st \\b\\f\\n\\r\\t\\"\\\\"
                resolution-algorithm = "pubgrub-v1"
                """;
        Lockfile rows = LockfileReader.readRows(text, "<test>");
        assertThat(rows.generatedBy()).isEqualTo("jk test \b\f\n\r\t\"\\");
        assertThat(rows).isEqualTo(LockfileReader.readToml(text, "<test>"));
    }

    @Test
    void text_outside_the_row_grammar_hands_off_to_toml() {
        String head = """
                version = 1
                generated-by = "jk 0.13.7"
                resolution-algorithm = "pubgrub-v1"
                """;
        List<String> outside = List.of(
                head + "# a comment\n",
                head + "kotlin = 'literal'\n",
                head + "kotlin = \"\"\"multi\"\"\"\n",
                head + "[jdk]\nsuggested-vendor = \"temurin\" # trailing\n",
                head + "[jdk.inner]\n",
                head + "jdk = { suggested-vendor = \"temurin\" }\n",
                head + "kotlin = \"a\"\nkotlin = \"b\"\n",
                head + "kotlin = \"\\x41\"\n",
                head + "[[artifact]]\nname = \"g:a\"\nversion = \"1\"\nsource = \"c\"\nscopes = [\"main\", 1]\n",
                "\uFEFF" + head,
                head.replace("version = 1", "version = 01"),
                head.replace("version = 1", "version = 1_0"),
                head.replace("version = 1", "version = +1"),
                head.replace("version = 1", "version = 1.0"),
                head + "[[artifact]]\n[artifact]\n",
                head + "[jdk]\n[jdk]\n");
        for (String text : outside) {
            assertThatThrownBy(() -> LockRowParser.parse(text)).as(text).isInstanceOf(LockRowParser.Unrecognised.class);
        }
        // The reader's answer on such text is tomlj's answer.
        Lockfile commented = LockfileReader.parse(head + "# a comment\nkotlin = 'literal'\n");
        assertThat(commented.kotlin()).isEqualTo("literal");
        assertThatThrownBy(() -> LockfileReader.parse(head + "kotlin = \"a\"\nkotlin = \"b\"\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse error");
    }

    @Test
    void a_value_of_the_wrong_type_is_diagnosed_by_toml() {
        String text = """
                version = "1"
                generated-by = "jk 0.13.7"
                resolution-algorithm = "pubgrub-v1"
                """;
        assertThatThrownBy(() -> LockfileReader.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void the_readers_own_diagnostics_come_from_the_row_grammar_too() {
        String text = """
                version = 1
                generated-by = "jk 0.13.7"
                resolution-algorithm = "pubgrub-v1"
                unknown-key = "x"
                """;
        assertThatThrownBy(() -> LockfileReader.readRows(text, "<test>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown top-level key")
                .hasMessageContaining("unknown-key");
    }

    @Test
    void every_lock_in_the_checkout_and_the_corpus_reads_identically_through_both_grammars() throws IOException {
        List<Path> locks = new ArrayList<>();
        PathUtil.forEachRegularFile(
                RepoRoot.find(LockRowParserTest.class),
                dir -> {
                    String name = dir.getFileName().toString();
                    return name.equals(".git") || name.equals("target") || name.equals("node_modules");
                },
                (file, attrs) -> {
                    if (file.getFileName().toString().equals("jk-lock.toml")) locks.add(file);
                });
        String corpus = System.getenv("JK_LOCK_CORPUS");
        if (corpus != null && !corpus.isBlank()) {
            for (String p : corpus.split(File.pathSeparator)) locks.add(Path.of(p));
        }
        assertThat(locks).isNotEmpty();
        int artifacts = 0;
        int refused = 0;
        for (Path lock : locks) {
            String text = Files.readString(lock);
            Lockfile toml;
            try {
                toml = LockfileReader.readToml(text, lock.toString());
            } catch (IllegalArgumentException refusal) {
                // A fixture lock this jk refuses is refused with the same words through the reader.
                String words = String.valueOf(refusal.getMessage()).replace(lock.toString(), "<string>");
                assertThatThrownBy(() -> LockfileReader.parse(text))
                        .as(lock.toString())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(words);
                refused++;
                continue;
            }
            Lockfile rows = LockfileReader.readRows(text, lock.toString());
            assertThat(rows).as(lock.toString()).isEqualTo(toml);
            assertThat(LockfileWriter.render(rows)).as(lock.toString()).isEqualTo(LockfileWriter.render(toml));
            artifacts += rows.artifacts().size();
        }
        System.out.println(locks.size() + " locks (" + refused + " refused by both), " + artifacts
                + " artifact rows read identically through both grammars");
    }
}
