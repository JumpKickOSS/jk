// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkInventoryTest {

    @Test
    void set_default_and_graal_are_independent(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path javaHome = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        Path graalHome = fakeGraal(jdks.resolve("graalvm-25.0.4"), "25.0.4");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("state/jk-jdks.toml"));

        inv.setDefault(new InstalledJdk("temurin-25.0.4", javaHome));
        inv.setGraal(new InstalledJdk("graalvm-25.0.4", graalHome));

        assertThat(inv.defaultId()).contains("temurin-25.0.4");
        assertThat(inv.graalId()).contains("graalvm-25.0.4");
        assertThat(inv.defaultHome()).contains(javaHome.toRealPath());
        assertThat(inv.graalHome()).contains(graalHome.toRealPath());

        String body = Files.readString(inv.file());
        assertThat(body)
                .contains("default = \"temurin-25.0.4\"")
                .contains("graal-default = \"graalvm-25.0.4\"")
                .contains("id = \"temurin-25.0.4\"")
                .contains("vendor = \"temurin\"")
                .contains("graal = false")
                .contains("id = \"graalvm-25.0.4\"")
                .contains("graal = true");

        inv.clearGraal();
        assertThat(inv.graalId()).isEmpty();
        assertThat(inv.defaultId()).contains("temurin-25.0.4");

        inv.clearDefault();
        assertThat(inv.defaultId()).isEmpty();
        assertThat(inv.graalId()).isEmpty();
        assertThat(Files.readString(inv.file())).contains("[[jdk]]");
    }

    @Test
    void replace_default_does_not_keep_the_old_id(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path first = fakeJdk(jdks.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        Path second = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.setDefault(new InstalledJdk("temurin-21.0.5", first));
        inv.setDefault(new InstalledJdk("temurin-25.0.4", second));
        assertThat(inv.defaultId()).contains("temurin-25.0.4");
        assertThat(Files.readString(inv.file())).contains("default = \"temurin-25.0.4\"");
        assertThat(Files.readString(inv.file()).lines().filter(l -> l.startsWith("default ")))
                .hasSize(1);
    }

    @Test
    void migrate_from_config_and_strip_legacy_keys(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path javaHome = fakeJdk(jdks.resolve("temurin-25.0.3"), "25.0.3", "Eclipse Adoptium");
        Path graalHome = fakeGraal(jdks.resolve("graalvm-25.0.3"), "25.0.3");
        Path config = tmp.resolve("config/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                color = "auto"
                default-jdk = "temurin-25.0.3"
                default-jdk-home = "%s"
                default-graal-jdk = "graalvm-25.0.3"
                default-graal-jdk-home = "%s"
                nerd-font = "auto"
                """.formatted(javaHome, graalHome), StandardCharsets.UTF_8);
        Path data = Files.createDirectories(tmp.resolve("data"));
        Files.createSymbolicLink(data.resolve("default-jdk"), javaHome);
        Files.createSymbolicLink(data.resolve("current-jdk"), javaHome);
        Files.createSymbolicLink(data.resolve("default-graal-jdk"), graalHome);

        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("state/jk-jdks.toml"), config, data);
        assertThat(inv.defaultId()).contains("temurin-25.0.3");
        assertThat(inv.graalId()).contains("graalvm-25.0.3");

        String leftover = Files.readString(config);
        assertThat(leftover).contains("color = \"auto\"").contains("nerd-font = \"auto\"");
        assertThat(leftover).doesNotContain("default-jdk").doesNotContain("default-graal");
        assertThat(Files.exists(data.resolve("default-jdk"))).isFalse();
        assertThat(Files.exists(data.resolve("current-jdk"))).isFalse();
        assertThat(Files.exists(data.resolve("default-graal-jdk"))).isFalse();
        assertThat(Files.readString(inv.file())).doesNotContain("sha256 =");
    }

    @Test
    void migrate_skips_stable_pointer_aliases(@TempDir Path tmp) throws IOException {
        assumeFalse(HostPlatform.isWindows());
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path real = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        Files.createSymbolicLink(jdks.resolve("temurin-25"), real);

        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.defaultId(); // trigger migrate
        String body = Files.readString(inv.file());
        assertThat(body).contains("id = \"temurin-25.0.4\"");
        assertThat(body).doesNotContain("id = \"temurin-25\"");
    }

    @Test
    void record_hashes_the_tree(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.record(new InstalledJdk("temurin-25.0.4", home), true);
        String body = Files.readString(inv.file());
        assertThat(body).contains("sha256 = \"");
        String sha = body.lines()
                .filter(l -> l.startsWith("sha256"))
                .map(l -> l.substring(l.indexOf('"') + 1, l.lastIndexOf('"')))
                .findFirst()
                .orElseThrow();
        assertThat(sha).isEqualTo(JdkFingerprint.compute(home));

        List<JdkInventory.Finding> findings = inv.verify();
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().kind()).isEqualTo(JdkInventory.Finding.Kind.OK);
    }

    @Test
    void verify_detects_tamper_missing_unhashed_untracked(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path hashed = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        Path unhashed = fakeJdk(jdks.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.record(new InstalledJdk("temurin-25.0.4", hashed), true);
        inv.record(new InstalledJdk("temurin-21.0.5", unhashed), false);
        Files.writeString(hashed.resolve("extra"), "x\n");
        Path extra = fakeJdk(jdks.resolve("corretto-25.0.4"), "25.0.4", "Amazon.com Inc.");

        List<JdkInventory.Finding> findings = inv.verify();
        assertThat(findings)
                .extracting(JdkInventory.Finding::kind)
                .contains(
                        JdkInventory.Finding.Kind.TAMPERED,
                        JdkInventory.Finding.Kind.UNHASHED,
                        JdkInventory.Finding.Kind.UNTRACKED);

        inv.repair();
        assertThat(inv.verify().stream().allMatch(JdkInventory.Finding::ok)).isTrue();
        assertThat(Files.readString(inv.file())).contains("id = \"corretto-25.0.4\"");
        assertThat(extra).isDirectory();
    }

    @Test
    void remove_clears_matching_defaults(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.setDefault(new InstalledJdk("temurin-25.0.4", home));
        inv.setGraal(new InstalledJdk("temurin-25.0.4", home));
        inv.remove("temurin-25.0.4");
        assertThat(inv.defaultId()).contains("temurin-25.0.4");
        assertThat(inv.graalId()).contains("temurin-25.0.4");
        assertThat(Files.readString(inv.file())).doesNotContain("[[jdk]]");
    }

    @Test
    void external_default_stores_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path external = fakeJdk(tmp.resolve("sdkman/25.0.4-tem"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.setDefault(new InstalledJdk("25.0.4-tem", external));
        assertThat(Files.readString(inv.file())).contains("home = ");
        assertThat(inv.defaultHome()).contains(external.toRealPath());
    }

    @Test
    void round_trip_parse_render(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.record(new InstalledJdk("temurin-25.0.4", home), true);
        inv.setDefault(new InstalledJdk("temurin-25.0.4", home));
        String once = Files.readString(inv.file());
        JdkInventory.Snapshot parsed = JdkInventory.parse(once);
        assertThat(JdkInventory.render(parsed)).isEqualTo(once);
    }

    @Test
    void external_and_unowned_defaults_verify_ok_and_survive_repair(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        // External home (sdkman-style): no ownership marker, jk never fingerprints it.
        Path external = fakeUnownedJdk(tmp.resolve("sdkman/25.0.4-tem"), "25.0.4", "Eclipse Adoptium");
        // Unowned tree in the shared jdks root (IntelliJ-installed): no ownership marker.
        Path unowned = fakeUnownedJdk(jdks.resolve("corretto-21.0.5"), "21.0.5", "Amazon.com Inc.");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.setDefault(new InstalledJdk("25.0.4-tem", external));
        inv.setGraal(new InstalledJdk("corretto-21.0.5", unowned));

        // Neither row can ever be UNHASHED or TAMPERED — jk doesn't own the bytes.
        assertThat(inv.verify().stream().allMatch(JdkInventory.Finding::ok)).isTrue();

        // Repair keeps both rows and both pointers — it must never clear a default whose home
        // still resolves, and never drop a row for a tree it merely doesn't own.
        inv.repair();
        assertThat(inv.defaultId()).contains("25.0.4-tem");
        assertThat(inv.graalId()).contains("corretto-21.0.5");
        assertThat(inv.defaultHome()).contains(external.toRealPath());
        assertThat(inv.verify().stream().allMatch(JdkInventory.Finding::ok)).isTrue();
    }

    @Test
    void migrate_synthesizes_a_row_for_an_external_legacy_default(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path external = fakeJdk(tmp.resolve("sdkman/25.0.4-tem"), "25.0.4", "Eclipse Adoptium");
        Path config = tmp.resolve("config/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                color = "auto"

                default-jdk = "25.0.4-tem"
                default-jdk-home = "%s"

                nerd-font = "auto"
                """.formatted(external), StandardCharsets.UTF_8);

        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("state/jk-jdks.toml"), config, null);
        assertThat(inv.defaultId()).contains("25.0.4-tem");
        // The pre-upgrade explicit default keeps resolving — the old scheme recorded the home
        // for exactly this case, and migration must not strand a bare id.
        assertThat(inv.defaultHome()).contains(external.toRealPath());

        // Legacy keys removed; everything else — including blank lines — byte-preserved.
        String leftover = Files.readString(config);
        assertThat(leftover).isEqualTo("""
                color = "auto"


                nerd-font = "auto"
                """);
    }

    @Test
    void migrate_leaves_a_config_without_legacy_keys_untouched(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path config = tmp.resolve("config/config.toml");
        Files.createDirectories(config.getParent());
        String original = """
                color = "auto"

                nerd-font = "auto"

                [m2]
                integration = true
                """;
        Files.writeString(config, original, StandardCharsets.UTF_8);
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("state/jk-jdks.toml"), config, null);
        inv.defaultId(); // trigger migrate
        assertThat(Files.readString(config)).isEqualTo(original);
    }

    @Test
    void home_with_backslashes_round_trips(@TempDir Path tmp) throws IOException {
        // A Windows home is written through MinimalToml.quote, which escapes each backslash;
        // the reader must decode them or the path grows double separators (UNC paths break).
        JdkInventory.Snapshot snap = JdkInventory.parse("""
                default = "corp-jdk"

                [[jdk]]
                id = "corp-jdk"
                vendor = "temurin"
                version = "25.0.4"
                graal = false
                home = "C:\\Users\\dev\\jdk-25"
                """);
        assertThat(snap.row("corp-jdk").home().toString()).isEqualTo("C:\\Users\\dev\\jdk-25".replace("\\\\", "\\"));
        // And render → parse is the identity on such a row.
        String rendered = JdkInventory.render(snap);
        assertThat(JdkInventory.parse(rendered).row("corp-jdk").home())
                .isEqualTo(snap.row("corp-jdk").home());
    }

    @Test
    void collision_between_owned_tree_and_external_default_resolves_to_the_recorded_home(@TempDir Path tmp)
            throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        Path external = fakeJdk(tmp.resolve("elsewhere/temurin-25.0.4"), "25.0.4", "Eclipse Adoptium");
        JdkInventory inv = new JdkInventory(jdks, tmp.resolve("jk-jdks.toml"));
        inv.setDefault(new InstalledJdk("temurin-25.0.4", external));
        // The row records WHICH install the user chose; the same-basename tree under the jdks
        // root must not shadow it.
        assertThat(inv.defaultHome()).contains(external.toRealPath());
    }

    private static Path fakeUnownedJdk(Path home, String version, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(home.resolve("bin").resolve("javac"), "#!/fake\n");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
        return home;
    }

    private static Path fakeJdk(Path home, String version, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(home.resolve("bin").resolve("javac"), "#!/fake\n");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
        JdkOwnership.mark(home);
        return home;
    }

    private static Path fakeGraal(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(home.resolve("bin").resolve("javac"), "#!/fake\n");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\""
                        + version
                        + "\"\nIMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM "
                        + version
                        + "\"\nGRAALVM_VERSION=\""
                        + version
                        + "\"\n");
        JdkOwnership.mark(home);
        return home;
    }
}
