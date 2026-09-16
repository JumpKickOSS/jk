// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class JdkInstallerTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();
    private String prevStateDir;
    private Path isolatedState;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        isolatedState = Files.createTempDirectory("jk-state-");
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", isolatedState.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        PathUtil.deleteRecursively(isolatedState);
    }

    @Test
    void installs_tar_gz_archive(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz(
                "jdk-21.0.5+11",
                Map.of(
                        "bin/java", "#!/fake/java",
                        "bin/javac", "#!/fake/java",
                        "release", "JAVA_VERSION=21.0.5\n"));

        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));

        JdkPackage pkg = new JdkPackage(
                "temurin",
                "21.0.5",
                "x64",
                "linux",
                "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);

        InstalledJdk installed = installer.install(pkg);
        assertThat(installed.identifier()).isEqualTo("21.0.5-tem-x64-linux");
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
    }

    @Test
    void two_installs_of_one_jdk_into_one_root_both_succeed_and_one_installs(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk-21.0.5+11", discoverableJdkFiles());
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkCatalog.Entry entry = new JdkCatalog.Entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of(),
                "linux",
                "x86_64",
                "targz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length,
                "jdk-21.0.5+11",
                "");
        // Two clients: each has probed, seen nothing, and downloaded; now both extract and move.
        JdkInstaller first = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkInstaller second = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkInstaller.DownloadedArchive firstArchive = first.download(entry, read -> {});
        JdkInstaller.DownloadedArchive secondArchive = second.download(entry, read -> {});
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<InstalledJdk> a = pool.submit(() -> {
                go.await();
                return first.extractInstalled(entry, firstArchive);
            });
            Future<InstalledJdk> b = pool.submit(() -> {
                go.await();
                return second.extractInstalled(entry, secondArchive);
            });
            go.countDown();
            InstalledJdk fromA = a.get(30, TimeUnit.SECONDS);
            InstalledJdk fromB = b.get(30, TimeUnit.SECONDS);

            assertThat(fromA.home()).isEqualTo(fromB.home()).isEqualTo(jdksRoot.resolve("temurin-21.0.5"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdksRoot.resolve("temurin-21.0.5/bin/java")).exists();
        assertThat(JdkOwnership.isJkOwned(jdksRoot.resolve("temurin-21.0.5"))).isTrue();
        assertThat(firstArchive.path()).doesNotExist();
        assertThat(secondArchive.path()).doesNotExist();
        try (var entries = Files.list(jdksRoot)) {
            assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".stage-")))
                    .isEmpty();
        }
    }

    @Test
    void a_target_populated_by_a_finished_install_is_adopted_and_the_stage_removed(@TempDir Path tempDir)
            throws Exception {
        byte[] archive = buildTarGz("jdk-21.0.5+11", discoverableJdkFiles());
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkCatalog.Entry entry = new JdkCatalog.Entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of(),
                "linux",
                "x86_64",
                "targz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length,
                "jdk-21.0.5+11",
                "");
        // The winner's tree, renamed into place after this client probed and downloaded — and not
        // yet marked, the gap between the winner's rename and its mark.
        Path target = jdksRoot.resolve("temurin-21.0.5");
        for (var file : discoverableJdkFiles().entrySet()) {
            Path f = target.resolve(file.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, file.getValue());
        }
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkInstaller.DownloadedArchive dl = installer.download(entry, read -> {});

        InstalledJdk installed = installer.extractInstalled(entry, dl);

        assertThat(installed.home()).isEqualTo(target);
        assertThat(target.resolve("bin/java")).hasContent("#!/fake/java");
        assertThat(JdkOwnership.isJkOwned(target))
                .as("the loser closes the winner's marking gap")
                .isTrue();
        assertThat(dl.path()).doesNotExist();
        try (var entries = Files.list(jdksRoot)) {
            assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".stage-")))
                    .as("the loser keeps nothing of its own")
                    .isEmpty();
        }
    }

    @Test
    void a_tree_at_the_target_that_is_not_a_jdk_is_not_answered_as_one(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz(
                "jdk-21.0.5+11",
                Map.of(
                        "bin/java", "#!/fake/java",
                        "bin/javac", "#!/fake/java",
                        "release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkCatalog.Entry entry = new JdkCatalog.Entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of(),
                "linux",
                "x86_64",
                "targz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length,
                "jdk-21.0.5+11",
                "");
        // Whatever sits at the target holds no launcher, so it is not an install that won a race.
        Path target = Files.createDirectories(jdksRoot.resolve("temurin-21.0.5"));
        Files.writeString(target.resolve("stray"), "");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkInstaller.DownloadedArchive dl = installer.download(entry, read -> {});

        assertThatThrownBy(() -> installer.extractInstalled(entry, dl)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(target.resolve("bin/java")).doesNotExist();
        assertThat(JdkOwnership.isJkOwned(target)).isFalse();
        assertThat(dl.path()).doesNotExist();
        try (var entries = Files.list(jdksRoot)) {
            assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".stage-")))
                    .isEmpty();
        }
    }

    @Test
    void stale_partial_downloads_are_swept_on_next_install(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz(
                "jdk-21.0.5+11",
                Map.of(
                        "bin/java", "#!/fake/java",
                        "bin/javac", "#!/fake/java",
                        "release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");

        // Seed the scratch dir with: an orphaned partial from a Ctrl-C'd run
        // (old mtime), a recent partial that could be a concurrent download, and
        // an unrelated file. Only the old jk-jdk-* one should be swept.
        Path downloads = Files.createDirectories(jdksRoot.resolve(".downloads"));
        Path stale = Files.writeString(downloads.resolve("jk-jdk-stale-.tar.gz"), "partial");
        Path fresh = Files.writeString(downloads.resolve("jk-jdk-fresh-.tar.gz"), "partial");
        Path unrelated = Files.writeString(downloads.resolve("keepme.txt"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkPackage pkg = new JdkPackage(
                "temurin",
                "21.0.5",
                "x64",
                "linux",
                "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);

        installer.install(pkg);

        assertThat(stale).doesNotExist(); // orphan from a canceled download — swept
        assertThat(fresh).exists(); // recent — left alone (may be a concurrent run)
        assertThat(unrelated).exists(); // not a jk-jdk-* partial — untouched
    }

    @Test
    void static_sweep_reclaims_orphans_without_downloading(@TempDir Path tempDir) throws Exception {
        // The entry point jk jdk uninstall/update call — sweeps with no download.
        Path jdksRoot = tempDir.resolve("jdks");

        // No scratch dir yet → must be a silent no-op, not an error.
        JdkInstaller.sweepStaleDownloads(jdksRoot);

        Path downloads = Files.createDirectories(jdksRoot.resolve(".downloads"));
        Path stale = Files.writeString(downloads.resolve("jk-jdk-stale-.tar.gz"), "partial");
        Path fresh = Files.writeString(downloads.resolve("jk-jdk-fresh-.tar.gz"), "partial");
        Path unrelated = Files.writeString(downloads.resolve("keepme.txt"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        JdkInstaller.sweepStaleDownloads(jdksRoot);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void sha256_mismatch_aborts_install(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk", Map.of("bin/java", "#!/fake", "bin/javac", "#!/fake"));
        served.put("/jdk.tar.gz", archive);

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin",
                "21.0.5",
                "x64",
                "linux",
                "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                "deadbeef",
                archive.length);

        assertThatThrownBy(() -> installer.install(pkg))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
    }

    @Test
    void appledouble_sidecars_are_skipped(@TempDir Path tempDir) throws Exception {
        // A macOS-built tarball can carry ._<name> sidecars next to real
        // entries. Installer must drop them so single-top-level flattening
        // still triggers and no junk lands in the JDK install directory.
        byte[] archive = buildTarGzRaw(new String[][] {
            {"jdk-21.0.5+11/", null},
            {"._jdk-21.0.5+11", "applesidecar"},
            {"jdk-21.0.5+11/bin/", null},
            {"jdk-21.0.5+11/._bin", "applesidecar"},
            {"jdk-21.0.5+11/bin/java", "#!/fake/java"},
            {"jdk-21.0.5+11/bin/javac", "#!/fake/java"},
            {"jdk-21.0.5+11/bin/._java", "applesidecar"},
            {"jdk-21.0.5+11/release", "JAVA_VERSION=21.0.5\n"},
            {"jdk-21.0.5+11/._release", "applesidecar"},
        });
        served.put("/jdk.tar.gz", archive);

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin",
                "21.0.5",
                "x64",
                "linux",
                "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);

        InstalledJdk installed = installer.install(pkg);
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
        assertThat(installed.home().resolve("._bin")).doesNotExist();
        assertThat(installed.home().resolve("._release")).doesNotExist();
        assertThat(installed.home().resolve("bin/._java")).doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_symlink_chain_cannot_create_directories_outside_the_destination(@TempDir Path tempDir) throws Exception {
        // d/l -> .. resolves to the extraction root; d/l/l2 -> .. is written through it, so it
        // lands in the root and points one level above; d/l/l2/pwn/ would then be created
        // beside the jdks root, and the file beneath it written there.
        byte[] archive = buildTarGzRaw(new String[][] {
            {"jdk/", null},
            {"jdk/bin/", null},
            {"jdk/bin/java", "#!/fake"},
            {"d/", null},
            {"d/l", null, ".."},
            {"d/l/l2", null, ".."},
            {"d/l/l2/pwn/", null},
            {"d/l/l2/pwn/owned", "outside"},
        });
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry = entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));
        assertThatThrownBy(() -> installer.install(entry))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes destination");

        assertThat(jdksRoot.resolve("pwn")).doesNotExist();
        assertThat(tempDir.resolve("pwn")).doesNotExist();
        try (var children = Files.list(jdksRoot)) {
            assertThat(children.filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith(".")))
                    .as("no install published from the refused archive")
                    .isEmpty();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_zip_directory_entry_routed_through_a_planted_link_is_refused_before_anything_is_created(
            @TempDir Path tempDir) throws Exception {
        // A zip carries no links of its own; the link is already in the tree it is unpacked into.
        Path dest = Files.createDirectories(tempDir.resolve("stage"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("lib"), outside);
        Path zip = Files.write(tempDir.resolve("jdk.zip"), buildZip(new String[][] {
            {"lib/pwn/", null},
            {"lib/pwn/owned", "outside"},
        }));

        assertThatThrownBy(() -> JdkInstaller.extract(zip, dest, "zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination")
                .hasMessageContaining("lib/pwn");
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_zip_file_entry_routed_through_a_planted_link_is_refused_before_anything_is_written(@TempDir Path tempDir)
            throws Exception {
        Path dest = Files.createDirectories(tempDir.resolve("stage"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("lib"), outside);
        Path zip = Files.write(tempDir.resolve("jdk.zip"), buildZip(new String[][] {
            {"bin/java", "#!/fake"},
            {"lib/owned", "outside"},
        }));

        assertThatThrownBy(() -> JdkInstaller.extract(zip, dest, "zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination")
                .hasMessageContaining("lib");
        assertThat(outside).isEmptyDirectory();
        assertThat(dest.resolve("bin/java"))
                .as("nothing is written before every entry is judged")
                .doesNotExist();
    }

    @Test
    void a_zip_entry_that_climbs_out_lexically_is_refused(@TempDir Path tempDir) throws Exception {
        Path dest = Files.createDirectories(tempDir.resolve("stage"));
        Path zip = Files.write(tempDir.resolve("jdk.zip"), buildZip(new String[][] {
            {"../owned", "outside"},
        }));

        assertThatThrownBy(() -> JdkInstaller.extract(zip, dest, "zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination");
        assertThat(tempDir.resolve("owned")).doesNotExist();
    }

    @Test
    void a_zip_unpacks_its_tree(@TempDir Path tempDir) throws Exception {
        Path dest = Files.createDirectories(tempDir.resolve("stage"));
        Path zip = Files.write(tempDir.resolve("jdk.zip"), buildZip(new String[][] {
            {"jdk/", null},
            {"jdk/bin/", null},
            {"jdk/bin/java", "#!/fake"},
            {"jdk/lib/modules", "mods"},
        }));

        JdkInstaller.extract(zip, dest, "zip");
        assertThat(dest.resolve("jdk/bin/java")).hasContent("#!/fake");
        assertThat(dest.resolve("jdk/lib/modules")).hasContent("mods");
    }

    @Test
    void an_entry_without_a_sha256_is_refused_before_anything_is_downloaded(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk", Map.of("bin/java", "#!/fake", "bin/javac", "#!/fake"));
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));

        for (String missing : new String[] {null, "", "   "}) {
            JdkCatalog.Entry entry = entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), missing);
            assertThatThrownBy(() -> installer.install(entry))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("carries no sha256")
                    .hasMessageContaining("temurin-21.0.5");
        }
        assertThat(jdksRoot.resolve("temurin-21.0.5")).doesNotExist();
        // The refusal happens before the request: nothing was staged or left half-downloaded.
        try (var files = Files.walk(jdksRoot)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void second_install_is_idempotent(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk", Map.of("bin/java", "x", "bin/javac", "x"));
        served.put("/jdk.tar.gz", archive);
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin",
                "21.0.5",
                "x64",
                "linux",
                "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);
        InstalledJdk first = installer.install(pkg);
        InstalledJdk second = installer.install(pkg);
        assertThat(second.home()).isEqualTo(first.home());
    }

    @Test
    void installs_jetbrains_catalog_entry(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz(
                "jdk-21.0.5",
                Map.of(
                        "bin/java", "#!/fake/java",
                        "bin/javac", "#!/fake/java",
                        "release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);

        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry = entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));

        InstalledJdk installed = installer.install(entry);
        assertThat(installed.identifier()).isEqualTo("temurin-21.0.5");
        assertThat(installed.home()).isEqualTo(jdksRoot.resolve("temurin-21.0.5"));
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(JdkOwnership.isJkOwned(jdksRoot.resolve("temurin-21.0.5"))).isTrue();
    }

    @Test
    void macos_entry_resolves_home_through_contents_home(@TempDir Path tempDir) throws Exception {
        // Tarball layout: jdk-21.0.5.jdk/Contents/Home/...
        byte[] archive = buildTarGz(
                "jdk-21.0.5.jdk",
                Map.of(
                        "Contents/Home/bin/java", "#!/fake/java",
                        "Contents/Home/bin/javac", "#!/fake/java",
                        "Contents/Home/release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);

        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry =
                entry("macOS", "aarch64", "Contents/Home", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));

        InstalledJdk installed = installer.install(entry);
        assertThat(installed.home())
                .isEqualTo(
                        jdksRoot.resolve("temurin-21.0.5").resolve("Contents").resolve("Home"));
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
    }

    @Test
    void installing_does_not_collect_a_superseded_jdk(@TempDir Path tempDir) throws Exception {
        // install provisions only; it does not drain JdkGarbage. Collecting belongs to the update verb.
        Path jdksRoot = Files.createDirectories(tempDir.resolve("jdks"));
        Path older = fakeJdk(jdksRoot, "temurin-21.0.4", "21.0.4");
        JdkOwnership.mark(older); // ours, and therefore collectable — by the update verb, not here
        Path current = fakeJdk(jdksRoot, "temurin-21.0.5", "21.0.5");
        JdkOwnership.mark(current);
        Path queue = jdksRoot.resolve(".to-be-removed");
        Files.writeString(queue, older.toRealPath() + System.lineSeparator());

        // The entry resolves to temurin-21.0.5, which is already on disk: no network, no download.
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        InstalledJdk got =
                installer.install(entry("linux", "x64", "", URI.create("https://example.invalid/x.tar.gz"), null));

        assertThat(got.identifier()).isEqualTo("temurin-21.0.5");
        assertThat(JdkFingerprint.java(older))
                .as("the superseded install is untouched by a provisioning call")
                .exists();
        assertThat(queue)
                .as("and its queue row is left for `jk jdk update`, which asked the user")
                .exists();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_link_that_leaves_the_lifted_root_refuses_the_install(@TempDir Path tempDir) throws Exception {
        // jdk/bin/x -> ../../other stays inside the staging directory while jdk/ wraps the tree;
        // installed, jdk/ becomes the root and the link points at a sibling of the install.
        byte[] archive = buildTarGzRaw(new String[][] {
            {"jdk/", null},
            {"jdk/bin/", null},
            {"jdk/bin/java", "#!/fake"},
            {"jdk/bin/x", null, "../../other"},
        });
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry = entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));
        assertThatThrownBy(() -> installer.install(entry))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the installed tree")
                .hasMessageContaining("bin/x -> ../../other");

        try (var children = Files.list(jdksRoot)) {
            assertThat(children.filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith(".")))
                    .as("no install published from the refused archive")
                    .isEmpty();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void links_inside_the_lifted_root_install(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGzRaw(new String[][] {
            {"jdk/", null},
            {"jdk/bin/", null},
            {"jdk/bin/java", "#!/fake"},
            {"jdk/lib/", null},
            {"jdk/lib/modules", "mods"},
            {"jdk/jre/", null},
            {"jdk/jre/lib", null, "../lib"},
        });
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        InstalledJdk installed = installer.install(
                entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive)));

        Path link = installed.home().resolve("jre/lib");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(link.resolve("modules")).hasContent("mods");
    }

    /** A JDK-shaped tree: enough for alreadyInstalled and JdkFingerprint to recognise it. */
    private static Path fakeJdk(Path root, String name, String version) throws IOException {
        Path home = root.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        return home;
    }

    private static JdkCatalog.Entry entry(
            String os, String arch, String javaHomeSubpath, URI url, @Nullable String sha256) {
        return new JdkCatalog.Entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of("temurin-21.0.5", "temurin-21", "21.0.5", "21"),
                os,
                arch,
                "targz",
                url,
                sha256,
                1024L,
                "temurin-21.0.5",
                javaHomeSubpath);
    }

    /**
     * Build a gzipped tar in-memory using Commons Compress — same library the installer reads with —
     * so the fixture matches what foojay serves without depending on a system {@code tar} binary (or
     * its platform-specific quirks, e.g. macOS AppleDouble sidecars).
     */
    /**
     * A tree the install probe accepts on this host: {@code bin/java} plus {@code .exe} launchers
     * on Windows, so a lost race can still recognise the winner.
     */
    private static Map<String, String> discoverableJdkFiles() {
        Map<String, String> files = new HashMap<>();
        files.put("bin/java", "#!/fake/java");
        files.put("bin/javac", "#!/fake/java");
        files.put("release", "JAVA_VERSION=21.0.5\n");
        if (Os.isWindows()) {
            files.put("bin/java.exe", "fake");
            files.put("bin/javac.exe", "fake");
        }
        return files;
    }

    private static byte[] buildTarGz(String topLevelDir, Map<String, String> entries) throws IOException {
        String[][] raw = new String[entries.size() + 1][];
        raw[0] = new String[] {topLevelDir + "/", null};
        int i = 1;
        for (var e : entries.entrySet()) {
            raw[i++] = new String[] {topLevelDir + "/" + e.getKey(), e.getValue()};
        }
        return buildTarGzRaw(raw);
    }

    /**
     * Build a tar.gz with entries written verbatim — no implicit top-level dir wrapping. Entry value
     * {@code null} means a directory entry.
     */
    /**
     * Build a tar.gz using hand-rolled 512-byte TAR blocks — no external library. {@code null} body =
     * directory entry; a third element makes the entry a symlink to that target.
     */
    private static byte[] buildTarGzRaw(String[][] rawEntries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (String[] e : rawEntries) {
            String name = e[0];
            String linkTarget = e.length > 2 ? e[2] : null;
            byte[] data = e[1] != null ? e[1].getBytes(StandardCharsets.UTF_8) : null;
            boolean isLink = linkTarget != null;
            boolean isDir = data == null && !isLink;
            byte[] header = new byte[512];
            // name (0-99)
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));
            // mode (100-107)
            putOctal(header, 100, 8, isDir ? 0755 : 0644);
            // uid/gid (108-115, 116-123)
            putOctal(header, 108, 8, 0);
            putOctal(header, 116, 8, 0);
            // size (124-135)
            putOctal(header, 124, 12, data != null ? data.length : 0);
            // mtime (136-147)
            putOctal(header, 136, 12, 0);
            // type (156)
            header[156] = (byte) (isLink ? '2' : isDir ? '5' : '0');
            // link target (157-256)
            if (linkTarget != null) {
                byte[] linkBytes = linkTarget.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(linkBytes, 0, header, 157, Math.min(linkBytes.length, 99));
            }
            // ustar magic (257-262)
            System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = ' ';
            header[264] = 0;
            // checksum (148-155): fill with spaces first, then compute
            Arrays.fill(header, 148, 156, (byte) ' ');
            int sum = 0;
            for (byte b : header) sum += (b & 0xFF);
            putOctal(header, 148, 8, sum);
            raw.write(header);
            if (data != null && data.length > 0) {
                raw.write(data);
                int pad = 512 - (data.length % 512);
                if (pad < 512) raw.write(new byte[pad]);
            }
        }
        raw.write(new byte[1024]); // two zero blocks = end of archive
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(raw.toByteArray());
        }
        return bytes.toByteArray();
    }

    /** A zip with the entries named verbatim; a {@code null} body is a directory entry. */
    private static byte[] buildZip(String[][] entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] e : entries) {
                zip.putNextEntry(new ZipEntry(e[0]));
                if (e[1] != null) zip.write(e[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void putOctal(byte[] buf, int off, int len, long value) {
        String octal = String.format("%0" + (len - 1) + "o", value);
        byte[] oBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(oBytes, 0, buf, off, Math.min(oBytes.length, len - 1));
        buf[off + len - 1] = 0;
    }
}
