// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import cc.jumpkick.lock.GraalPin;
import cc.jumpkick.lock.JdkPin;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.ToolchainSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainLockStampTest {

    @Test
    void stamps_jdk_from_the_selected_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = apply(home, registry, ToolchainSpec.parse("jdk", "25"), false);
        // The declared "25" is what the lock records for the version — a floor on the major, not
        // the patch. No vendor was declared, so the one that resolved fills that field in.
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("temurin", "25"));
        assertThat(stamped.graal()).isNull();
    }

    @Test
    void stamps_graal_when_the_project_declared_it_and_one_is_installed(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = apply(java, registry, ToolchainSpec.NONE, true);
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("temurin", "25.0.4"));
        assertThat(stamped.graal()).isEqualTo(GraalPin.suggested("graalvm-ce", "25.0.4"));
    }

    @Test
    void stamps_both_tables_from_a_graal_java_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal = fakeJdk(
                jdks.resolve("graalvm-25.0.4"),
                "25.0.4",
                "Oracle Corporation",
                "IMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\nGRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = apply(graal, registry, ToolchainSpec.NONE, false);
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("graalvm", "25.0.4"));
        assertThat(stamped.graal()).isEqualTo(GraalPin.suggested("graalvm", "25.0.4"));
    }

    @Test
    void records_the_declared_jdk_even_when_the_host_has_a_different_major(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // The host has only 25; the project asked for 17. The declaration is what gets recorded —
        // stamping the host's 25 is what would let a floor read as met and leave 17 unprovisioned.
        Lockfile stamped = apply(home, registry, ToolchainSpec.parse("jdk", "17"), false);
        assertThat(requireNonNull(stamped.jdk()).suggestedVersion()).isEqualTo("17");
    }

    @Test
    void records_the_resolved_jdk_when_the_project_declares_none(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // Nothing declared, so the lock records what built it. That is a suggestion and a floor on
        // the major — it does not hold a later build to Temurin, or to 25.0.4.
        Lockfile stamped = apply(home, registry, ToolchainSpec.NONE, false);
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("temurin", "25.0.4"));
    }

    @Test
    void an_equals_pin_becomes_required_and_leaves_the_suggestion_empty(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = apply(home, registry, ToolchainSpec.parse("jdk", "=corretto-25.0.4"), false);
        assertThat(stamped.jdk()).isEqualTo(new JdkPin("", "", "corretto", "25.0.4"));
    }

    @Test
    void an_equals_vendor_with_a_bare_major_pins_only_the_vendor(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // No patch to be exact about, so the major stays a floor and the = binds the vendor.
        Lockfile stamped = apply(home, registry, ToolchainSpec.parse("jdk", "=temurin-25"), false);
        assertThat(stamped.jdk()).isEqualTo(new JdkPin("", "25", "temurin", ""));
    }

    @Test
    void graal_records_what_resolved_when_a_bare_native_block_declared_it(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // [native] with no graal names no vendor, so whichever distribution resolves is recorded.
        Lockfile stamped = apply(java, registry, ToolchainSpec.NONE, true);
        assertThat(stamped.graal()).isEqualTo(GraalPin.suggested("graalvm-ce", "25.0.4"));
    }

    @Test
    void no_graal_table_when_nothing_declared_it(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // The machine has a GraalVM; no manifest asked for one. An ambient install is still not a
        // declaration — the [jdk] table records what built the lock, [graal] answers a question
        // nobody put. This is the shape was filed against.
        Lockfile stamped = apply(java, registry, ToolchainSpec.NONE, false);
        assertThat(stamped.graal()).isNull();
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("temurin", "25.0.4"));
    }

    private static Lockfile apply(Path home, JdkRegistry registry, ToolchainSpec jdk, boolean graalDeclared) {
        return requireNonNull(ToolchainLockStamp.apply(
                Lockfile.empty("0.1"), null, home, registry, jdk, ToolchainSpec.NONE, graalDeclared));
    }

    @Test
    void a_conservative_relock_keeps_the_suggestion_it_found(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // Someone else locked this on Corretto. Re-locking here must not rewrite the record of
        // what built it just because this machine runs Temurin — only jk update refreshes that.
        Lockfile previous = Lockfile.empty("0.1").withJdk(JdkPin.suggested("corretto", "25.0.1"));
        Lockfile kept = requireNonNull(ToolchainLockStamp.apply(
                Lockfile.empty("0.1"), previous, home, registry, ToolchainSpec.NONE, ToolchainSpec.NONE, false));
        assertThat(kept.jdk()).isEqualTo(JdkPin.suggested("corretto", "25.0.1"));

        // jk update passes no previous, so the suggestion moves to what resolved.
        Lockfile refreshed = apply(home, registry, ToolchainSpec.NONE, false);
        assertThat(refreshed.jdk()).isEqualTo(JdkPin.suggested("temurin", "25.0.4"));
    }

    @Test
    void a_conservative_relock_drops_an_unknown_vendor_suggestion(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile previous = Lockfile.empty("0.1").withJdk(JdkPin.suggested("nosuchvendor", "99"));
        Lockfile rewritten = requireNonNull(ToolchainLockStamp.apply(
                Lockfile.empty("0.1"), previous, home, registry, ToolchainSpec.NONE, ToolchainSpec.NONE, false));
        assertThat(rewritten.jdk()).isEqualTo(JdkPin.suggested("temurin", "25.0.4"));
    }

    @Test
    void a_declaration_still_beats_the_previous_lock(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile previous = Lockfile.empty("0.1").withJdk(JdkPin.suggested("corretto", "25.0.1"));
        Lockfile stamped = requireNonNull(ToolchainLockStamp.apply(
                Lockfile.empty("0.1"),
                previous,
                home,
                registry,
                ToolchainSpec.parse("jdk", "microsoft-26"),
                ToolchainSpec.NONE,
                false));
        assertThat(stamped.jdk()).isEqualTo(JdkPin.suggested("microsoft", "26"));
    }

    private static Path fakeJdk(Path home, String version, String implementor, @Nullable String extra)
            throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake\n");
        String release = "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n"
                + (extra == null ? "" : extra);
        Files.writeString(home.resolve("release"), release);
        JdkOwnership.mark(home);
        return home.toRealPath();
    }
}
