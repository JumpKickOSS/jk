// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ConfigReference;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ~/.jk/config.toml}, a project {@code jk.toml} and Maven's {@code settings.xml} read their
 * {@code ${...}} through one tokenizer, {@link ConfigReference}, under two grammars. The readers
 * of those files are pinned to it: none of them carries its own {@code ${} scanner.
 */
class OneReferenceTokenizerTest {

    /** A regex for {@code ${}, or a string test for one: the shapes a second tokenizer takes. */
    private static final Pattern SCANNER =
            Pattern.compile("\\\\\\\\\\$\\\\\\\\\\{|(?:indexOf|contains|startsWith|split)\\(\"\\$\\{\"");

    /** The packages that read config.toml, jk.toml and settings.xml values. */
    private static final List<String> READERS = List.of(
            "shared/core/src/main/java/cc/jumpkick/config",
            "shared/client-io/src/main/java/cc/jumpkick/m2",
            "shared/client-io/src/main/java/cc/jumpkick/repo");

    @Test
    void only_config_reference_scans_for_a_reference() throws IOException {
        List<String> scanners = new ArrayList<>();
        for (String dir : READERS) {
            String module = dir.substring(0, dir.indexOf("/src/"));
            Path root =
                    RepoRoot.dir(OneReferenceTokenizerTest.class, module).resolve(dir.substring(module.length() + 1));
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file :
                        files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("ConfigReference.java")) continue;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (SCANNER.matcher(lines.get(i)).find()) scanners.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(scanners)
                .as("a ${ scanner outside ConfigReference is a second answer to what a reference is")
                .isEmpty();
    }

    /** The probe above must see the shapes it looks for, or an empty result proves nothing. */
    @Test
    void the_probe_sees_a_regex_and_a_string_scanner() {
        assertThat(SCANNER.matcher("Pattern.compile(\"\\\\$\\\\{([^}]+)}\")").find())
                .isTrue();
        assertThat(SCANNER.matcher("if (!raw.contains(\"${\")) return raw;").find())
                .isTrue();
        assertThat(SCANNER.matcher("\"${\" + var + \"}\"").find()).isFalse();
    }

    @Test
    void the_two_grammars_read_their_own_spellings() {
        String value = "${env:A} ${B} ${file:~/s#k} ${cmd:helper get} ${env.C} ${user.home} ${in} ${}";

        assertThat(ConfigReference.all(value))
                .extracting(r -> r.scheme() + ":" + r.body())
                .containsExactly("ENV:A", "ENV:B", "FILE:~/s#k", "CMD:helper get", "ENV:in");
        assertThat(ConfigReference.scan(value, ConfigReference::mavenExpression))
                .containsExactly("env:A", "B", "file:~/s#k", "cmd:helper get", "env.C", "user.home", "in");
    }

    /** A body the grammar does not read is no token: the scan moves on and finds the one inside it. */
    @Test
    void a_body_the_grammar_rejects_is_left_and_the_scan_resumes_inside_it() {
        assertThat(ConfigReference.expand(
                        "x ${not a name ${HOME}} y", ConfigReference::parse, r -> "[" + r.body() + "]"))
                .isEqualTo("x ${not a name [HOME]} y");
        assertThat(ConfigReference.expand("${A} $ {B} ${C", ConfigReference::parse, r -> null))
                .as("an unresolved token stays as written")
                .isEqualTo("${A} $ {B} ${C");
        assertThat(ConfigReference.expand("$${A}}", ConfigReference::parse, r -> "v"))
                .isEqualTo("$v}");
    }

    /** settings.xml through the tokenizer, end to end: expansion and the unexpanded-is-not-ciphertext rule. */
    @Test
    void settings_xml_expands_through_the_maven_grammar(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <servers>
                    <server><id>a</id><username>${env.U}</username><password>{x}${env.UNSET}</password></server>
                  </servers>
                </settings>
                """);
        MavenSettings settings = MavenSettings.loadFrom(xml, Map.of("U", "deployer")::get);

        assertThat(settings.server("a")).hasValueSatisfying(s -> {
            assertThat(s.username()).isEqualTo("deployer");
            assertThat(s.password()).isEqualTo("{x}${env.UNSET}");
        });
        assertThat(settings.warnings()).singleElement().asString().contains("${env.UNSET}");
    }
}
