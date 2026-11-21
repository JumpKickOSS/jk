// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.run.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tomlj.Toml;
import org.tomlj.TomlTable;

/**
 * A {@code jk.toml} value of the wrong type, or a key its table does not know, fails the parse
 * with a {@link JkBuildParseException} at that key: never tomlj's own exception, never a silent drop.
 */
class TomlTableReaderTest {

    private static final String HEAD = "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n";

    @TempDir
    Path tmp;

    private Path manifest(String body) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, HEAD + body.replace("|", "\n"));
        return f;
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "[manifest]|Foo = 1; manifest.Foo must be a string",
                "[variants.flavor]|default = 1|[variants.flavor.free]; [variants.flavor].default must be a string",
                "[image]|base = 1; image.base must be a string",
                "[image]|aot-cache = 'yes'; image.aot-cache must be true or false",
                "[image]|ports = 1; image.ports must be an array",
                "[build]|lint = 'no'; [build].lint must be true or false",
                "[build]|extra-src = 'x'; [build].extra-src must be an array",
                "[test]|workers = '2'; [test].workers must be an integer >= 0",
                "[test]|jvm-args = 'x'; [test].jvm-args must be an array of JVM flags",
                "[dependencies]|foo = { group = 'g', name = 'a', version = '1', optional = 'yes' }; dependencies.foo.optional must be true or false",
                "[dependencies]|foo = { group = 'g', name = 'a', version = '1', classifier = 1 }; dependencies.foo.classifier must be a string",
                "[dependencies]|foo = { group = 'g', name = 'a', version = 1 }; dependencies.foo.version must be a string",
                "[dependencies]|foo = { git = 'https://x/y.git', submodules = 'no' }; dependencies.foo.submodules must be true or false",
                "[native]|main = 1; [native].main must be a string",
                "[resolve]|platform = 1; [resolve].platform must be a string",
                "[application]|main = 1; [application].main must be a string",
                "[profiles.dev]|inherits = 1; profiles.dev.inherits must be a string",
                "[[kotlin-plugins]]|coordinate = 1; [[kotlin-plugins]].coordinate must be a string",
                "[plugins]|foo = { sha256 = 1 }; plugins.foo.sha256 must be a string",
                "[workspace]|modules = ['a']|[workspace.dependencies]|foo = { group = 1, version = '1' }; workspace.dependencies.foo.group must be a string",
                "[m2]|integration = 'yes'; [m2].integration must be true or false",
                "[resources]|properties = 1; [resources] properties must be a table",
                "description = 1; description must be a string",
                "layout = 1; layout must be a string",
                "kotlin = 1; kotlin must be a version string",
                "variants = 1; `variants` must be a table",
                "manifest = 1; `manifest` must be a table",
                "repositories = 1; `repositories` must be a table",
                "profiles = 1; `profiles` must be a table",
                "features = 1; `features` must be a table",
                "application = 1; `application` must be a table",
                "library = 1; `library` must be a table",
                "workspace = 1; `workspace` must be a table",
                "test = 1; `test` must be a table",
                "dependencies = 1; [dependencies] must be a table",
                "[image]|bogus = 1; [image] unknown key `bogus`",
                "[test]|bogus = 1; [test] unknown key `bogus`",
            })
    void a_mistyped_or_unknown_key_is_a_parse_error(String body, String message) throws Exception {
        Path f = manifest(body.replace('\'', '"'));
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "[train]|command = 1; [train] command must be a string",
                "[train]|require-fresh = 'yes'; [train] require-fresh must be true or false",
                "[train]|bogus = 1; [train] unknown key `bogus`",
                "[[train.profile]]|name = 'p'|bogus = 1; unknown key `bogus`",
                "[[train.profile]]|name = 'p'|args = [1]; to be a list of strings",
                "train = 1; `train` must be a table",
            })
    void the_train_table_is_strict(String body, String message) throws Exception {
        Path f = manifest(body.replace('\'', '"'));
        assertThatThrownBy(() -> JkBuildParser.trainConfig(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "[deny]|yanked = 1; deny.yanked must be a string",
                "[deny]|bogus = 1; [deny] unknown key `bogus`",
                "[deny.sources]|allow = ['x']; [deny.sources] unknown key `allow`",
                "[deny.licenses]|deny = 'GPL-*'; [deny.licenses] deny must be an array",
                "deny = 1; `deny` must be a table",
            })
    void the_deny_table_is_strict(String body, String message) throws Exception {
        Path f = manifest(body.replace('\'', '"'));
        assertThatThrownBy(() -> JkBuildParser.denyPolicy(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "[workspace]|modules = ['a']|bogus = 1; [workspace] unknown key `bogus`",
                "guards = 1; `guards` must be a table",
                "[guards]|bogus = 1; [guards] unknown key `bogus`",
                "graph = 1; `graph` must be a table",
                "[graph]|bogus = 1; [graph] unknown key `bogus`",
                "release = 1; `release` must be a table",
                "[release]|bogus = 1; [release] unknown key `bogus`",
                "deny = 1; `deny` must be a table",
                "[deny]|bogus = 1; [deny] unknown key `bogus`",
                "train = 1; `train` must be a table",
                "[train]|bogus = 1; [train] unknown key `bogus`",
                "node = true; node must be a string",
                "[node]|bogus = 1; [node] unknown key `bogus`",
                "jvm = 1; `jvm` must be a table",
                "[jvm]|max-ram-precent = 50; [jvm] unknown key `max-ram-precent`",
                "[jvm]|gc = 1; jvm.gc must be a string",
                "[jvm]|gc = 'zgcc'; jvm.gc must be one of default, none, g1, zgc, parallel, serial",
                "[jvm]|string-dedup = 'no'; jvm.string-dedup must be true or false",
                "[jvm]|max-ram-percent = '50'; jvm.max-ram-percent must be a number above 0 and at most 100",
                "[jvm]|max-ram-percent = 0; jvm.max-ram-percent must be a number above 0 and at most 100",
                "[jvm]|max-ram-percent = 150; jvm.max-ram-percent must be a number above 0 and at most 100",
                "[jvm]|args = '-Xmx2g'; `jvm.args` to be a list of strings",
                "[jvm]|args = [1]; `jvm.args` to be a list of strings",
            })
    void a_table_another_command_reads_is_checked_by_every_parse(String body, String message) throws Exception {
        Path f = manifest(body.replace('\'', '"'));
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(message);
    }

    @Test
    void a_jvm_table_with_every_key_parses() throws Exception {
        Path f = manifest("[jvm]\nmax-ram-percent = 12.5\ngc = \"ZGC\"\nstring-dedup = false\nargs = [\"-Xss2m\"]");
        JkBuildParser.parse(f);
        assertThat(JkBuildParser.jvmTuning(f)).isEqualTo(new PluginTuning(12.5, "ZGC", false, List.of("-Xss2m")));
    }

    @Test
    void a_bare_node_number_is_the_node_version() throws Exception {
        assertThat(JkBuildParser.parse(manifest("node = 1"))
                        .project()
                        .nodeSpec()
                        .version())
                .isEqualTo("1");
    }

    @Test
    void a_refusal_is_located_at_the_key() throws Exception {
        Path f = manifest("[image]\nname = \"app\"\nbase = 1\n");
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOfSatisfying(JkBuildParseException.class, e -> assertThat(e.location())
                        .isEqualTo(SourceLocation.at(6, 1).in(f)));
    }

    @Test
    void a_key_is_one_key_even_with_a_dot_in_it() {
        TomlTable table = Toml.parse("\"a.b\" = \"x\"\n[a]\nb = 1\n");
        assertThat(TomlTableReader.optionalString(table, "a.b", "a.b")).isEqualTo("x");
        assertThat(TomlTableReader.optionalInt(table, "missing", "missing")).isNull();
        assertThatThrownBy(() -> TomlTableReader.optionalInt(Toml.parse("n = 3000000000\n"), "n", "n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("n must be an integer");
        assertThatThrownBy(() -> TomlTableReader.rejectUnknownKeys(table, List.of("a.b"), "[root]"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[root] unknown key `a` — expected one of: a.b");
    }
}
