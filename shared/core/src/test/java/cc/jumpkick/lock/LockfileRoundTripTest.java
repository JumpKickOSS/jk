// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Scope;
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
                version = 2
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
                version = 2
                generated-by = "jk 0.9.0"
                resolution-algorithm = "pubgrub-v1"
                jk = { version = "0.9.0", sha256 = "abcd" }
                """;
        Lockfile parsed = LockfileReader.parse(legacy);
        assertThat(parsed.jkMin()).isEqualTo("0.9.0");
        assertThat(LockfileWriter.render(parsed)).contains("jk-min = \"0.9.0\"").doesNotContain("sha256 = \"abcd\"");
    }

    @Test
    void legacy_bare_local_source_reads_as_jk_local() {
        // Pre-rename lockfiles marked first-party file-dep entries with the bare source "local".
        // A user remote named local always carries its URL ("local+file://…"), so the bare form is
        // unambiguous and folds to the current marker; the URL form must pass through untouched.
        String legacy = """
                version = 2
                generated-by = "jk 0.12.0"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name = "com.acme:filedep"
                version = "1.0.0"
                source = "local"
                checksum = "sha256:abcd"

                [[artifact]]
                name = "com.acme:remote"
                version = "2.0.0"
                source = "local+file:///srv/repo"
                checksum = "sha256:ef01"
                """;
        Lockfile parsed = LockfileReader.parse(legacy);
        assertThat(parsed.artifacts().get(0).source()).isEqualTo("jk-local");
        assertThat(parsed.artifacts().get(1).source()).isEqualTo("local+file:///srv/repo");
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
                        List.of(Scope.MAIN),
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
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT", Lockfile.JdkPin.suggested("temurin", "25.0.3"))
                .withKotlin("2.3.21");
        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("kotlin = \"2.3.21\"");

        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.kotlin()).isEqualTo("2.3.21");
        assertThat(parsed.jdk()).isEqualTo(Lockfile.JdkPin.suggested("temurin", "25.0.3"));
    }

    @Test
    void jdk_and_graal_pins_round_trip() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT", Lockfile.JdkPin.suggested("temurin", "25.0.4.1"))
                .withGraal(Lockfile.GraalPin.suggested("graalvm-ce", "25.0.4"));
        String rendered = LockfileWriter.render(original);
        assertThat(rendered)
                .contains("[jdk]\nsuggested-vendor = \"temurin\"\nsuggested-version = \"25.0.4.1\"\n")
                .contains("[graal]\nsuggested-vendor = \"graalvm-ce\"\nsuggested-version = \"25.0.4\"\n")
                .doesNotContain("jdk = ")
                .doesNotContain("required-");
        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.jdk()).isEqualTo(original.jdk());
        assertThat(parsed.graal()).isEqualTo(original.graal());
    }

    @Test
    void required_pins_round_trip_and_leave_the_suggestion_out() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT", new Lockfile.JdkPin("", "25", "microsoft", ""))
                .withGraal(new Lockfile.GraalPin("", "", "graalvm-ce", "25.0.4"));
        String rendered = LockfileWriter.render(original);
        assertThat(rendered)
                .contains("[jdk]\nsuggested-version = \"25\"\nrequired-vendor = \"microsoft\"\n")
                .contains("[graal]\nrequired-vendor = \"graalvm-ce\"\nrequired-version = \"25.0.4\"\n");
        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.jdk()).isEqualTo(original.jdk());
        assertThat(parsed.graal()).isEqualTo(original.graal());
    }

    @Test
    void the_old_vendor_version_shape_is_rejected_rather_than_guessed_at() {
        String legacy = """
                version = 2
                generated-by = "jk 0.1.0"
                resolution-algorithm = "nearest-wins"

                [jdk]
                vendor  = "temurin"
                version = "25.0.4"
                """;
        assertThatThrownBy(() -> LockfileReader.parse(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("re-run `jk lock`");
    }

    @Test
    void a_v1_lock_is_rejected_because_its_toolchain_tables_cannot_be_read() {
        String v1 = """
                version = 1
                generated-by = "jk 0.1.0"
                resolution-algorithm = "nearest-wins"
                """;
        assertThatThrownBy(() -> LockfileReader.parse(v1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("re-run `jk lock`");
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
                                ".", "com.example", "root", "1.2.3", 25, null, null, "Root", null, null),
                        new Lockfile.ModuleEntry(
                                "lib",
                                "com.example",
                                "lib",
                                "1.2.3",
                                25,
                                "2.4.10",
                                null,
                                null,
                                "publish",
                                false,
                                false)));

        String rendered = LockfileWriter.render(original);
        assertThat(rendered)
                .contains("[[module]]")
                .contains("path    = \".\"")
                .contains("path    = \"lib\"")
                .contains("version = \"1.2.3\"")
                .doesNotContain("jdk     =")
                .contains("java    = 25")
                .contains("kotlin  = \"2.4.10\"")
                .contains("sources = \"publish\"")
                .contains("m2.integration = false")
                .contains("m2.install = false");

        // Whole records, not selected keys: a component the writer forgets to emit is a silent
        // data loss on the next read, and a per-field assertion list never notices the new one.
        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.modules()).containsExactlyElementsOf(original.modules());
    }

    @Test
    void module_scala_pin_round_trips() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT")
                .withModules(List.of(new Lockfile.ModuleEntry(
                        ".", "com.example", "app", "1.0.0", 25, null, null, "3", null, null, null, null)));
        String rendered = LockfileWriter.render(original);
        assertThat(rendered).contains("scala   = \"3\"");
        assertThat(LockfileReader.parse(rendered).modules().getFirst().scala()).isEqualTo("3");
    }

    /**
     * The reachability-metadata repository is an input to {@code native-image} and therefore a lock
     * fact (JK-2476). It sits in its own {@code [native]} table rather than an {@code [[artifact]]}
     * row: it is on no classpath and in no scope, and the artifact table is the solver's output.
     */
    @Test
    void the_native_metadata_pin_round_trips() {
        Lockfile original = Lockfile.empty("0.1.0-SNAPSHOT")
                .withNativeMetadata(new Lockfile.NativeMetadata("1.2.0", "sha256:" + "ab".repeat(32)))
                .withArtifacts(List.of(new Lockfile.Artifact(
                        "com.example:widget", "1.0.0", "central", null, null, List.of(Scope.MAIN), List.of())));
        String rendered = LockfileWriter.render(original);

        assertThat(rendered).contains("[native]\nmetadata-repository = \"1.2.0\"\n");
        // The [[artifact]] rows below must not have been swallowed into [native].
        Lockfile parsed = LockfileReader.parse(rendered);
        assertThat(parsed.artifacts()).hasSize(1);
        assertThat(parsed.nativeMetadata()).isEqualTo(original.nativeMetadata());
        assertThat(parsed.nativeMetadata().checksumHex()).isEqualTo("ab".repeat(32));
    }

    /** Locks written before the pin existed read as "no pin", not as a version to guess at. */
    @Test
    void a_lock_without_the_native_table_has_no_pin() {
        assertThat(LockfileReader.parse("""
                        version = 2
                        generated-by = "jk 0.9.0"
                        resolution-algorithm = "pubgrub-v1"
                        """).nativeMetadata()).isNull();
        // A table with no version is the same as no table: there is nothing to extract.
        assertThat(LockfileReader.parse("""
                        version = 2
                        generated-by = "jk 0.9.0"
                        resolution-algorithm = "pubgrub-v1"

                        [native]
                        checksum = "sha256:dead"
                        """).nativeMetadata()).isNull();
    }
}
