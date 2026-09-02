// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkEnvTest {

    @Test
    void empty_when_no_jk_toml_anywhere(@TempDir Path tempDir) throws IOException {
        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/home/u/.jk/bin", noGlobalDefault(tempDir));
        var target = env.resolve(tempDir);
        assertThat(target.isActive()).isFalse();
        assertThat(target.projectRoot()).isEmpty();
        assertThat(target.vars()).isEmpty();
    }

    @Test
    void empty_when_jk_toml_exists_but_lock_has_no_jdk(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/home/u/.jk/bin", noGlobalDefault(tempDir));
        assertThat(env.resolve(project).isActive()).isFalse();
    }

    @Test
    void resolves_jdk_home_from_registry(@TempDir Path tempDir) throws IOException {
        // Stand up a fake jk-managed JDK install + a project with jk-lock.toml
        // pointing at it.
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = jdksRoot.resolve("temurin-25.0.3");
        fakeJdk(jdkHome);

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.3")), project.resolve("jk-lock.toml"));

        String livePath = "/home/u/.jk/bin" + File.pathSeparator + "/home/u/bin";
        var env = new JkEnv(new JdkRegistry(jdksRoot), livePath, noGlobalDefault(tempDir));
        var target = env.resolve(project);

        // ProbeSupport canonicalises JDK homes via toRealPath() — on macOS
        // the @TempDir path lives under /var/folders/... which symlinks to
        // /private/var/folders/..., so the expected JAVA_HOME must be the
        // real path, not the raw @TempDir.
        var realJdkHome = jdkHome.toRealPath();
        assertThat(target.isActive()).isTrue();
        assertThat(target.projectRoot()).contains(project.toAbsolutePath().normalize());
        assertThat(target.vars().get("JAVA_HOME")).isEqualTo(realJdkHome.toString());
        assertThat(target.vars().get("PATH")).isEqualTo(realJdkHome.resolve("bin") + File.pathSeparator + livePath);
    }

    @Test
    void sets_graalvm_home_when_jdk_is_graalvm(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = jdksRoot.resolve("graalvm-jdk-25");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.writeString(JdkFingerprint.java(jdkHome), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(jdkHome), "#!/fake\n");
        Files.writeString(
                jdkHome.resolve("release"),
                "JAVA_VERSION=\"25.0.0\"\nIMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\n");
        JdkOwnership.mark(jdkHome);

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("graalvm-jdk", "25")), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", noGlobalDefault(tempDir));
        var target = env.resolve(project);

        // Canonicalise: see resolves_jdk_home_from_registry for the macOS
        // /var → /private/var symlink rationale.
        var realJdkHome = jdkHome.toRealPath();
        assertThat(target.vars()).containsEntry("GRAALVM_HOME", realJdkHome.toString());
        assertThat(target.vars()).containsEntry("JAVA_HOME", realJdkHome.toString());
    }

    @Test
    void walks_up_from_subdirectory_to_find_project_root(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("proj");
        var nested = project.resolve("src/main/java/com/example");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");

        // No JDK pinned → still walks up successfully but yields empty target.
        var found = JkEnv.findProjectRoot(nested);
        assertThat(found).contains(project.toAbsolutePath().normalize());
    }

    @Test
    void unknown_jdk_identifier_yields_empty_target(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("nonexistent-jdk", "999")),
                project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/home/u/.jk/bin", noGlobalDefault(tempDir));
        assertThat(env.resolve(project).isActive()).isFalse();
    }

    @Test
    void falls_back_to_default_jdk_outside_a_project(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        // A bare directory with no jk.toml anywhere — yet the configured default
        // JDK still lands on PATH so `java`/`javac` resolve. It must live OUTSIDE the repo tree:
        // the build's java.io.tmpdir is build/tmp (inside the checkout), so a @TempDir has the
        // repo's own jk.toml as an ancestor and resolve would find that project.
        var noProject = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".jk-env-test-");
        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        var target = env.resolve(noProject);

        var realHome = jdkHome.toRealPath();
        assertThat(target.isActive()).isTrue();
        assertThat(target.projectRoot()).isEmpty();
        assertThat(target.vars().get("JAVA_HOME")).isEqualTo(realHome.toString());
        assertThat(target.vars().get("PATH"))
                .isEqualTo(realHome.resolve("bin") + File.pathSeparator + "/home/u/.jk/bin");
        Files.deleteIfExists(noProject);
    }

    @Test
    void path_swap_keeps_nvm_and_replaces_prior_jdk_bin(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.3")), project.resolve("jk-lock.toml"));

        String livePath = "/home/u/.jdks/old/bin"
                + File.pathSeparator
                + "/home/u/.nvm/versions/node/v24/bin"
                + File.pathSeparator
                + "/home/u/.jk/bin";
        var env = new JkEnv(new JdkRegistry(jdksRoot), livePath, noGlobalDefault(tempDir), "/home/u/.jdks/old", null);
        var target = env.resolve(project);

        var realBin = jdkHome.toRealPath().resolve("bin").toString();
        assertThat(target.vars().get("PATH"))
                .isEqualTo(realBin
                        + File.pathSeparator
                        + "/home/u/.nvm/versions/node/v24/bin"
                        + File.pathSeparator
                        + "/home/u/.jk/bin");
    }

    @Test
    void falls_back_to_default_when_project_has_no_pin(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        var target = env.resolve(project);

        assertThat(target.isActive()).isTrue(); // no pin → default fills in
        assertThat(target.vars().get("JAVA_HOME"))
                .isEqualTo(jdkHome.toRealPath().toString());
    }

    @Test
    void lock_jdk_beats_global_default(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        fakeJdk(jdksRoot.resolve("temurin-21.0.5"), "21.0.5");
        var j25 = fakeJdk(jdksRoot.resolve("temurin-25.0.3"), "25.0.3");
        var defaults = globalDefaultConfig(tempDir, "temurin-21.0.5");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.3")), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        var target = env.resolve(project);
        assertThat(target.vars().get("JAVA_HOME")).isEqualTo(j25.toRealPath().toString());
    }

    @Test
    void lock_accepts_newer_patch_of_the_same_vendor(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var newer = fakeJdk(jdksRoot.resolve("temurin-25.0.4"), "25.0.4");
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.3")), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", noGlobalDefault(tempDir));
        assertThat(env.resolve(project).vars().get("JAVA_HOME"))
                .isEqualTo(newer.toRealPath().toString());
    }

    @Test
    void unmet_lock_major_does_not_export_a_too_old_default(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        fakeJdk(jdksRoot.resolve("temurin-21.0.5"), "21.0.5");
        var defaults = globalDefaultConfig(tempDir, "temurin-21.0.5");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.4")), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        assertThat(env.resolve(project).isActive()).isFalse();
    }

    @Test
    void lock_graal_beats_de_facto_graal(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var javaHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"), "25.0.3");
        var lockedCe = fakeGraal(jdksRoot.resolve("25.0.3-graalce"), "25.0.3");
        fakeGraal(jdksRoot.resolve("25.0.4-graal"), "25.0.4", true);
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(
                Lockfile.empty("0.1", Lockfile.JdkPin.suggested("temurin", "25.0.3"))
                        .withGraal(Lockfile.GraalPin.suggested("graalvm-ce", "25.0.3")),
                project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        var target = env.resolve(project);
        assertThat(target.vars().get("JAVA_HOME"))
                .isEqualTo(javaHome.toRealPath().toString());
        assertThat(target.vars().get("GRAALVM_HOME"))
                .isEqualTo(lockedCe.toRealPath().toString());
    }

    @Test
    void exports_graalvm_home_for_sole_installed_graal_without_graal_default(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var javaHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var graalHome = fakeGraal(jdksRoot.resolve("25.2.4-graalce"));
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "group=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk-lock.toml"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/home/u/.jk/bin", defaults);
        var target = env.resolve(project);

        assertThat(target.vars().get("JAVA_HOME"))
                .isEqualTo(javaHome.toRealPath().toString());
        assertThat(target.vars().get("GRAALVM_HOME"))
                .isEqualTo(graalHome.toRealPath().toString());
        assertThat(target.vars().get("PATH"))
                .startsWith(javaHome.toRealPath().resolve("bin")
                        + File.pathSeparator
                        + graalHome.toRealPath().resolve("bin"));
    }

    /** An inventory with no rows and no default. */
    private static JdkInventory noGlobalDefault(Path tempDir) {
        return new JdkInventory(tempDir.resolve("jdks"), tempDir.resolve("jk-jdks.toml"));
    }

    /** Inventory whose {@code default} is {@code id} (the tree must already exist under {@code jdks/}). */
    private static JdkInventory globalDefaultConfig(Path tempDir, String id) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        JdkInventory inv = new JdkInventory(jdks, tempDir.resolve("jk-jdks.toml"));
        Path home = jdks.resolve(id);
        inv.setDefault(new InstalledJdk(id, home));
        return inv;
    }

    /** Stand up a fake jk-managed JDK install (bin/java, bin/javac, release) and return its home. */
    private static Path fakeJdk(Path home) throws IOException {
        return fakeJdk(home, "25.0.3");
    }

    private static Path fakeJdk(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake\n");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return home;
    }

    private static Path fakeGraal(Path home) throws IOException {
        return fakeGraal(home, "25.0.4", false);
    }

    private static Path fakeGraal(Path home, String version) throws IOException {
        return fakeGraal(home, version, false);
    }

    private static Path fakeGraal(Path home, String version, boolean oracle) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake\n");
        String implementor = oracle ? "Oracle Corporation" : "GraalVM Community";
        String extra = oracle
                ? "IMPLEMENTOR_VERSION=\"Oracle GraalVM " + version + "\"\nGRAALVM_VERSION=\"" + version + "\"\n"
                : "GRAALVM_VERSION=\"" + version + "\"\n";
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n" + extra);
        JdkOwnership.mark(home);
        return home;
    }
}
