// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LockfileRoundTripTest {

    @Test
    void empty_lockfile_renders_deterministically() {
        Lockfile lock = Lockfile.empty("0.1.0-SNAPSHOT");
        String rendered = LockfileWriter.render(lock);

        // The header is deterministic; a floor-less lock is stamped with the FORMAT floor —
        // never the running version (the floor moves only when the lock format requires it).
        assertThat(rendered).startsWith("""
                version = 1
                generated-by = "jk 0.1.0-SNAPSHOT"
                resolution-algorithm = "pubgrub-v1"
                """);
        assertThat(rendered).contains("\njk-min = \"" + LockfileWriter.FORMAT_FLOOR + "\"\n");
        assertThat(rendered).doesNotContain("jk = {");
    }

    @Test
    void jk_floor_round_trips_and_legacy_pin_reads_as_the_floor() {
        Lockfile lock = Lockfile.empty("0.1.0-SNAPSHOT").withJkMin("0.11.0");
        String rendered = LockfileWriter.render(lock);
        assertThat(rendered).contains("jk-min = \"0.11.0\"");
        assertThat(LockfileReader.parse(rendered).jkMin()).isEqualTo("0.11.0");

        // A legacy artifact pin reads as the floor; the sha is ignored (a floor needs no engine
        // artifact) and the next write renders it in floor form.
        String legacy = """
                version = 1
                generated-by = "jk 0.9.0"
                resolution-algorithm = "pubgrub-v1"
                jk = { version = "0.9.0", sha256 = "abcd" }
                """;
        Lockfile parsed = LockfileReader.parse(legacy);
        assertThat(parsed.jkMin()).isEqualTo("0.9.0");
        assertThat(LockfileWriter.render(parsed)).contains("jk-min = \"0.9.0\"").doesNotContain("sha256 = \"abcd\"");
    }

    @Test
    void git_source_package_round_trips() {
        Lockfile.Artifact.GitInfo git = new Lockfile.Artifact.GitInfo(
                "https://github.com/acme/widgets", "3f2a9c1b4d5e6f70819203a4b5c6d7e8f9012345", "tag:v1.4.0");
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.acme:widgets",
                        "1.4.0",
                        "git+https://github.com/acme/widgets",
                        "sha256:abcd",
                        null,
                        List.of(cc.jumpkick.model.Scope.MAIN),
                        List.of(),
                        null,
                        git)));

        String rendered = LockfileWriter.render(lock);
        assertThat(rendered)
                .contains("git      = \"https://github.com/acme/widgets\"")
                .contains("rev      = \"3f2a9c1b4d5e6f70819203a4b5c6d7e8f9012345\"")
                .contains("ref      = \"tag:v1.4.0\"");

        Lockfile reparsed = LockfileReader.parse(rendered);
        assertThat(reparsed.artifacts()).singleElement().satisfies(p -> assertThat(p.git())
                .isEqualTo(git));
    }

    @Test
    void packages_render_sorted_by_name_then_version() {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        new Lockfile.Artifact(
                                "com.b:beta",
                                "1.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:bbbb",
                                null,
                                List.of()),
                        new Lockfile.Artifact(
                                "com.a:alpha",
                                "2.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:aaaa",
                                null,
                                List.of("com.b:beta@1.0.0"))));

        String rendered = LockfileWriter.render(lock);
        int alphaIdx = rendered.indexOf("com.a:alpha");
        int betaIdx = rendered.indexOf("com.b:beta");
        assertThat(alphaIdx).isPositive();
        assertThat(betaIdx).isGreaterThan(alphaIdx);
    }

    @Test
    void round_trip_preserves_content() {
        Lockfile original = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.example:widget",
                        "1.2.3",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:0123abcd",
                        null,
                        List.of("com.example:dep@2.0.0"))));

        String rendered = LockfileWriter.render(original);
        Lockfile parsed = LockfileReader.parse(rendered);

        assertThat(parsed.version()).isEqualTo(original.version());
        assertThat(parsed.generatedBy()).isEqualTo(original.generatedBy());
        assertThat(parsed.resolutionAlgorithm()).isEqualTo(original.resolutionAlgorithm());
        assertThat(parsed.artifacts()).hasSize(1);
        assertThat(parsed.artifacts().getFirst().name()).isEqualTo("com.example:widget");
        assertThat(parsed.artifacts().getFirst().deps()).containsExactly("com.example:dep@2.0.0");
    }

    @Test
    void kotlin_version_round_trips() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT", "temurin-25.0.3").withKotlin("2.3.21");
        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("kotlin = \"2.3.21\"");

        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.kotlin()).isEqualTo("2.3.21");
        assertThat(parsed.jdk()).isEqualTo("temurin-25.0.3");
    }

    @Test
    void absent_kotlin_is_not_rendered() {
        String rendered = LockfileWriter.render(Lockfile.empty("0.1.0-SNAPSHOT"));
        assertThat(rendered).doesNotContain("kotlin =");
        assertThat(LockfileReader.parse(rendered).kotlin()).isNull();
    }

    @Test
    void scala_compiler_pin_round_trips() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT").withScala("3.8.4");
        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("scala = \"3.8.4\"");
        assertThat(LockfileReader.parse(rendered).scala()).isEqualTo("3.8.4");
        assertThat(LockfileWriter.render(Lockfile.empty("0.1.0-SNAPSHOT"))).doesNotContain("scala =");
    }

    @Test
    void module_entries_round_trip() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT")
                .withModules(List.of(
                        new Lockfile.ModuleEntry(
                                ".",
                                "com.example",
                                "root",
                                "1.2.3",
                                "temurin-25",
                                25,
                                null,
                                null,
                                "Root",
                                null,
                                null,
                                null),
                        new Lockfile.ModuleEntry(
                                "lib",
                                "com.example",
                                "lib",
                                "1.2.3",
                                "temurin-25",
                                25,
                                "2.4.0",
                                null,
                                null,
                                "publish",
                                false,
                                false,
                                "maven")));

        String rendered = LockfileWriter.render(original);
        assertThat(rendered)
                .contains("[[module]]")
                .contains("path    = \".\"")
                .contains("path    = \"lib\"")
                .contains("version = \"1.2.3\"")
                .contains("jdk     = \"temurin-25\"")
                .contains("java    = 25")
                .contains("kotlin  = \"2.4.0\"")
                .contains("sources = \"publish\"")
                .contains("m2integration = false")
                .contains("m2install = false");
        assertThat(rendered).doesNotContain("layout");

        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.modules()).hasSize(2);
        assertThat(parsed.modules().getFirst().name()).isEqualTo("root");
        assertThat(parsed.modules().get(1).name()).isEqualTo("lib");
        assertThat(parsed.modules().get(1).version()).isEqualTo("1.2.3");
        assertThat(parsed.modules().get(1).java()).isEqualTo(25);
        assertThat(parsed.modules().get(1).kotlin()).isEqualTo("2.4.0");
        assertThat(parsed.modules().get(1).m2integration()).isFalse();
        assertThat(parsed.modules().get(1).m2install()).isFalse();
    }

    @Test
    void module_scala_pin_round_trips() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT")
                .withModules(List.of(new Lockfile.ModuleEntry(
                        ".",
                        "com.example",
                        "app",
                        "1.0.0",
                        "temurin-25",
                        25,
                        null,
                        null,
                        "3",
                        null,
                        null,
                        null,
                        null,
                        null)));
        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("scala   = \"3\"");
        assertThat(LockfileReader.parse(rendered).modules().getFirst().scala()).isEqualTo("3");
    }
}
