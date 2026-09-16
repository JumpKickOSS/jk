// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.engine.ReleaseArtifacts;
import cc.jumpkick.cli.engine.StubReleaseDirectory;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.Classpaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the spy jar is looked for, how it is attached, and how a release install that has none
 * fetches its own version's — held to the release directory's signed manifest like the engine jar.
 */
@Tag("integration")
class MavenSpyJarTest {

    private static final String VERSION = "9.9.9";
    private static final String JAR_NAME = "jk-maven-spy-" + VERSION + ".jar";
    private static final byte[] JAR = "fake spy jar bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private StubReleaseDirectory release;

    @BeforeEach
    void start() throws IOException {
        release = new StubReleaseDirectory(VERSION);
        release.put(JAR_NAME, JAR);
    }

    @AfterEach
    void stop() {
        release.close();
    }

    private MavenSpyJar spy(Path lib, boolean fetchable) {
        return new MavenSpyJar(
                null, VERSION, lib, List.of(), new MavenSpyJar.Release(release.base(), release.verifier(), fetchable));
    }

    @Test
    void arguments_prepend_the_spy_and_the_events_file() {
        Path jar = Path.of("/lib/jk-maven-spy-1.jar");
        Path events = Path.of("/tmp/e.jsonl");
        assertThat(MavenSpyJar.arguments(jar, events, List.of("-q", "test")))
                .containsExactly(
                        "-Djk.mvn.events=/tmp/e.jsonl", "-Dmaven.ext.class.path=/lib/jk-maven-spy-1.jar", "-q", "test");
    }

    @Test
    void a_users_extension_path_keeps_its_entries_and_gains_the_spy() {
        Path jar = Path.of("/lib/spy.jar");
        List<String> out = MavenSpyJar.arguments(
                jar, Path.of("/tmp/e.jsonl"), List.of("-Dmaven.ext.class.path=/x/a.jar", "verify"));
        assertThat(out)
                .containsExactly(
                        "-Djk.mvn.events=/tmp/e.jsonl",
                        "-Dmaven.ext.class.path=/x/a.jar" + Classpaths.SEPARATOR + "/lib/spy.jar",
                        "verify");
    }

    @Test
    void the_override_wins_when_it_names_a_file_and_is_skipped_when_it_does_not() throws Exception {
        Path jar = Files.createFile(tmp.resolve("spy.jar"));
        Path lib = tmp.resolve("lib");
        MavenSpyJar.Release none = new MavenSpyJar.Release(release.base(), release.verifier(), false);
        assertThat(new MavenSpyJar(jar, VERSION, lib, List.of(), none).locate())
                .contains(jar.toAbsolutePath().normalize());
        assertThat(new MavenSpyJar(tmp.resolve("missing.jar"), VERSION, lib, List.of(), none).locate())
                .isEmpty();
    }

    @Test
    void the_lookup_runs_property_then_library_then_fallbacks() throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path shelf = Files.createFile(tmp.resolve("shelf-" + JAR_NAME));
        MavenSpyJar.Release none = new MavenSpyJar.Release(release.base(), release.verifier(), false);
        MavenSpyJar spy = new MavenSpyJar(null, VERSION, lib, List.of(shelf), none);
        assertThat(spy.locate()).contains(shelf.toAbsolutePath().normalize());

        Path inLib = Files.createFile(lib.resolve(JAR_NAME));
        assertThat(spy.locate()).contains(inLib.toAbsolutePath().normalize());
    }

    @Test
    void a_release_client_fetches_the_missing_jar_into_the_product_library() throws Exception {
        Path lib = tmp.resolve("home").resolve("lib");
        MavenSpyJar spy = spy(lib, true);
        assertThat(spy.locate()).isEmpty();

        assertThat(spy.ensure()).contains(lib.resolve(JAR_NAME).toAbsolutePath().normalize());

        assertThat(lib.resolve(JAR_NAME)).hasBinaryContent(JAR);
        assertThat(release.requested()).containsExactly("SHA256SUMS", "SHA256SUMS.sig", JAR_NAME);
        try (Stream<Path> files = Files.list(lib)) {
            assertThat(files).as("no temp file is left beside the jar").containsExactly(lib.resolve(JAR_NAME));
        }
        // Present now: the next run finds it without asking the release.
        assertThat(spy.ensure()).isPresent();
        assertThat(release.requested()).hasSize(3);
    }

    @Test
    void a_checkout_or_offline_client_does_not_reach_for_the_release() {
        Path lib = tmp.resolve("lib");
        assertThat(spy(lib, false).ensure()).isEmpty();
        assertThat(release.requested()).isEmpty();
        assertThat(lib).doesNotExist();
    }

    @Test
    void a_jar_the_manifest_disagrees_with_is_refused_and_nothing_is_written() {
        release.freezeSums();
        release.put(JAR_NAME, "tampered".getBytes(StandardCharsets.UTF_8));
        Path lib = tmp.resolve("lib");

        assertThatThrownBy(() -> spy(lib, true).fetch(ReleaseArtifacts.Progress.NONE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Maven spy jar checksum mismatch");
        assertThat(lib).doesNotExist();
    }

    @Test
    void a_failed_fetch_is_one_line_on_stderr_and_maven_still_runs_without_the_spy() {
        release.status(JAR_NAME, 404);
        Path lib = tmp.resolve("lib");
        MavenSpyJar spy = spy(lib, true);

        AtomicReference<Optional<Path>> found = new AtomicReference<>();
        String err = Capture.stderr(() -> found.set(spy.ensure()));

        assertThat(found.get()).isEmpty();
        assertThat(err)
                .contains("jk mvn: " + JAR_NAME + " could not be fetched")
                .contains("HTTP 404")
                .contains("Maven runs without a run report");
    }
}
