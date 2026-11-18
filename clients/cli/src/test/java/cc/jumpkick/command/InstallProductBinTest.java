// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.command.pipeline.InstallCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [install] product-bin} — jk's own client replacing the PATH client, with the JVM launcher
 * {@code jk-jvm} beside it. The freshness question is the destination's, as with {@code
 * product-lib}: the engine's forecast knows the build output, not whether {@code bin/jk} holds it
 * or whether the launcher is there at all.
 */
class InstallProductBinTest {

    @Test
    void the_built_binary_replaces_jk_and_jkx_parking_the_previous_client_only_on_windows(@TempDir Path tmp)
            throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        String nativeName = BuildLayout.nativeExecutableFileName("jk");
        Files.writeString(bin.resolve(nativeName), "old client");
        Files.writeString(bin.resolve("jkx"), "old client");
        Path built =
                Files.writeString(Files.createDirectories(tmp.resolve("target")).resolve(nativeName), "new client");

        Path installed = new EngineInstall(tmp.resolve("lib"))
                .pass(null, null)
                .placeNative(built, bin, new Cas(tmp.resolve("cas")), null);

        assertThat(installed).isEqualTo(bin.resolve(nativeName));
        assertThat(bin.resolve(nativeName)).hasContent("new client");
        String jkxName = Os.isWindows() ? "jkx.exe" : "jkx";
        assertThat(bin.resolve(jkxName)).hasContent("new client");
        // POSIX renames the new client straight over the live one; only Windows, which cannot
        // replace a running executable, parks the previous client aside.
        if (Os.isWindows()) {
            assertThat(bin.resolve(nativeName + ".old")).hasContent("old client");
        } else {
            assertThat(bin.resolve(nativeName + ".old")).doesNotExist();
        }
        // The bin entry is not an alias of target/: rebuilding there leaves the install alone.
        Files.writeString(built, "next build");
        assertThat(bin.resolve(nativeName)).hasContent("new client");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_checkout_install_records_the_client_it_replaces_and_a_rollback_puts_it_back(@TempDir Path tmp)
            throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Cas cas = new Cas(tmp.resolve("cas"));
        EngineInstall pointer = new EngineInstall(tmp.resolve("lib"));
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path clientA = Files.writeString(target.resolve("jk-a"), "client 0.51.0-SNAPSHOT");
        Path clientB = Files.writeString(target.resolve("jk-b"), "client 0.51.0");
        Path engineA = Files.writeString(target.resolve("jk-engine-a.jar"), "engine 0.51.0-SNAPSHOT");
        Path engineB = Files.writeString(target.resolve("jk-engine-b.jar"), "engine 0.51.0");

        // The snapshot install, then the release install over it: each pass puts the client in
        // place before the engine, as a checkout install orders them.
        installJk(pointer, clientA, bin, cas, "aaaa", engineA, "0.51.0-SNAPSHOT");
        installJk(pointer, clientB, bin, cas, null, engineB, "0.51.0");

        EngineInstall.Install previous = pointer.previousInstall().orElseThrow();
        assertThat(previous.version()).isEqualTo("0.51.0-SNAPSHOT");
        EngineInstall.InstalledClient replaced = Objects.requireNonNull(previous.client());
        assertThat(replaced.sha256()).isEqualTo(Hashing.sha256Hex(clientA));
        assertThat(replaced.commit()).isEqualTo("aaaa");
        assertThat(pointer.liveClient().orElseThrow().sha256()).isEqualTo(Hashing.sha256Hex(clientB));

        pointer.rollback(cas, EngineInstall.nativeClients(bin));

        assertThat(EngineInstall.pathClient(bin)).hasContent("client 0.51.0-SNAPSHOT");
        assertThat(pointer.currentInstall().orElseThrow().version()).isEqualTo("0.51.0-SNAPSHOT");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_pass_that_replaces_only_the_client_keeps_the_previous_install(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Cas cas = new Cas(tmp.resolve("cas"));
        EngineInstall pointer = new EngineInstall(tmp.resolve("lib"));
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path engineA = Files.writeString(target.resolve("jk-engine-a.jar"), "engine A");
        Path engineB = Files.writeString(target.resolve("jk-engine-b.jar"), "engine B");
        installJk(pointer, Files.writeString(target.resolve("jk-a"), "client A"), bin, cas, null, engineA, "1.0.0");
        installJk(pointer, Files.writeString(target.resolve("jk-b"), "client B"), bin, cas, null, engineB, "1.1.0");

        Path clientC = Files.writeString(target.resolve("jk-c"), "client C");
        EngineInstall.Pass pass = pointer.pass("1.1.0", null);
        pass.placeNative(clientC, bin, cas, null);
        pass.finish();

        assertThat(pointer.liveClient().orElseThrow().sha256()).isEqualTo(Hashing.sha256Hex(clientC));
        EngineInstall.Install previous = pointer.previousInstall().orElseThrow();
        assertThat(previous.version()).isEqualTo("1.0.0");
        assertThat(Objects.requireNonNull(previous.client()).sha256())
                .isEqualTo(Hashing.sha256Hex(target.resolve("jk-a")));
        assertThat(EngineInstall.pathClient(bin)).hasContent("client C");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_snapshot_of_the_installed_release_installs_nothing(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Cas cas = new Cas(tmp.resolve("cas"));
        EngineInstall pointer = new EngineInstall(tmp.resolve("lib"));
        Path target = Files.createDirectories(tmp.resolve("target"));
        installJk(
                pointer,
                Files.writeString(target.resolve("jk-r"), "client 0.51.0"),
                bin,
                cas,
                null,
                Files.writeString(target.resolve("jk-engine-r.jar"), "engine 0.51.0"),
                "0.51.0");
        String pointerBefore = Files.readString(pointer.configFile());

        assertThatThrownBy(() -> pointer.pass("0.51.0-SNAPSHOT", "0.51.0-SNAPSHOT"))
                .isInstanceOf(IOException.class)
                .hasMessage("jk 0.51.0 is installed, newer than 0.51.0-SNAPSHOT; nothing was installed"
                        + " (-F replaces it, or a private JK_HOME takes it)");

        assertThat(EngineInstall.pathClient(bin)).hasContent("client 0.51.0");
        assertThat(pointer.configFile()).hasContent(pointerBefore);
    }

    @Test
    void a_forced_pass_installs_an_older_engine_over_a_newer_one(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        EngineInstall pointer = new EngineInstall(tmp.resolve("lib"));
        Path target = Files.createDirectories(tmp.resolve("target"));
        pointer.materializeFromFiles("0.52.1-SNAPSHOT", cas, Files.writeString(target.resolve("new.jar"), "new"));

        EngineInstall.Pass pass = pointer.pass(null, "0.52.0", true);
        pass.engine("0.52.0", cas, Files.writeString(target.resolve("old.jar"), "old"));
        pass.finish();

        assertThat(pointer.currentInstall().orElseThrow().version()).isEqualTo("0.52.0");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_client_whose_engine_the_pass_does_not_install_is_refused(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Cas cas = new Cas(tmp.resolve("cas"));
        EngineInstall pointer = new EngineInstall(tmp.resolve("lib"));
        Path target = Files.createDirectories(tmp.resolve("target"));
        installJk(
                pointer,
                Files.writeString(target.resolve("jk-a"), "client A"),
                bin,
                cas,
                null,
                Files.writeString(target.resolve("jk-engine-a.jar"), "engine A"),
                "1.0.0");

        assertThatThrownBy(() -> pointer.pass("1.1.0", null))
                .isInstanceOf(IOException.class)
                .hasMessage("client 1.1.0 needs its engine, and this install has engine 1.0.0; nothing was installed");
        assertThat(EngineInstall.pathClient(bin)).hasContent("client A");
    }

    /** One checkout install pass of {@code version}: the client placed, then the engine. */
    private static void installJk(
            EngineInstall pointer,
            Path client,
            Path bin,
            Cas cas,
            @Nullable String commit,
            Path engineJar,
            String version)
            throws Exception {
        EngineInstall.Pass pass = pointer.pass(version, version);
        pass.placeNative(client, bin, cas, commit);
        pass.engine(version, cas, engineJar);
        pass.finish();
    }

    @Test
    void the_path_client_is_stale_until_it_holds_the_built_bytes(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve(AppLauncher.launcherFileName("jk-jvm")), "launcher");
        String nativeName = BuildLayout.nativeExecutableFileName("jk");
        Path built = Files.writeString(tmp.resolve(nativeName), "built");
        ProjectInfo info = info("jk", built.toString());

        assertThat(InstallCommand.productBinStale(info, bin))
                .as("nothing installed yet")
                .isTrue();
        Files.writeString(bin.resolve(nativeName), "older");
        assertThat(InstallCommand.productBinStale(info, bin)).as("other bytes").isTrue();
        Files.writeString(bin.resolve(nativeName), "built");
        assertThat(InstallCommand.productBinStale(info, bin)).isFalse();
    }

    @Test
    void a_home_without_the_jvm_launcher_is_stale_whatever_the_client_holds(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        String nativeName = BuildLayout.nativeExecutableFileName("jk");
        Path built = Files.writeString(tmp.resolve(nativeName), "built");
        Files.writeString(bin.resolve(nativeName), "built");
        ProjectInfo info = info("jk", built.toString());

        assertThat(InstallCommand.productBinStale(info, bin))
                .as("the client is current but the launcher beside it is missing")
                .isTrue();
        Files.writeString(bin.resolve(AppLauncher.launcherFileName("jk-jvm")), "launcher");
        assertThat(InstallCommand.productBinStale(info, bin)).isFalse();
    }

    @Test
    void an_ordinary_module_is_never_product_bin_stale(@TempDir Path tmp) throws Exception {
        assertThat(InstallCommand.productBinStale(info("", tmp.resolve("jk").toString())))
                .isFalse();
        assertThat(InstallCommand.productBinStale((ProjectInfo) null)).isFalse();
    }

    @Test
    void a_client_that_was_never_built_is_not_stale_once_the_launcher_is_there(@TempDir Path tmp) throws Exception {
        ProjectInfo info = info("jk", tmp.resolve("missing").toString());
        assertThat(InstallCommand.productBinStale(info, tmp))
                .as("no native client, no launcher: the launcher is owed")
                .isTrue();
        Files.writeString(tmp.resolve(AppLauncher.launcherFileName("jk-jvm")), "launcher");
        assertThat(InstallCommand.productBinStale(info, tmp)).isFalse();
    }

    private static ProjectInfo info(String productBin, String nativeBinPath) {
        ProjectInfo base = ProjectInfo.error(null);
        return new ProjectInfo(
                null,
                "cc.jumpkick",
                "jk-cli",
                "0.1.0",
                base.jdk(),
                base.javaRelease(),
                base.kotlin(),
                base.kotlinVersion(),
                base.groovy(),
                base.groovyVersion(),
                base.layoutSimple(),
                base.workspaceRoot(),
                base.workspaceRootDir(),
                base.moduleDirs(),
                true,
                "cc.jumpkick.cli.Jk",
                false,
                base.applicationConfig(),
                "ALWAYS",
                base.graal(),
                base.springBoot(),
                base.springBootVersion(),
                base.formatStyle(),
                base.formatJava(),
                base.formatKotlin(),
                base.formatOptimizeImports(),
                base.formatImportOrder(),
                base.formatRemoveUnusedImports(),
                base.hasLock(),
                base.lockJdk(),
                base.mainJarPath(),
                base.assemblyJarPath(),
                nativeBinPath,
                base.nativeLibPath(),
                base.pathDeps(),
                base.sourcesJarPath(),
                base.javadocJarPath(),
                base.envRefs(),
                base.moduleNames(),
                base.sourceCount(),
                base.testCount(),
                base.nativeExplicitlyDisabled(),
                base.classesDir(),
                base.testClassesDir(),
                base.kotlinClassesDir(),
                base.groovyClassesDir(),
                base.testResultsDir(),
                base.lockStale(),
                base.scala(),
                base.scalaVersion(),
                base.coordinatorOnly(),
                "",
                productBin,
                Map.of(),
                base.publishRepository(),
                base.publishSign(),
                base.publishKeyFile(),
                base.releaseTag(),
                base.releaseFiles(),
                base.upstreams(),
                base.releaseBuild(),
                base.releaseLine(),
                List.of(),
                false,
                List.of());
    }
}
