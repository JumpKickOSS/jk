// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.IsolatedHome;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RecordingProxy;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk new} on a fresh home with no network: no catalogue clone exists and none can be made, so
 * the default template is the {@code app} jk ships in its own distribution, and the project it
 * writes passes {@code jk test} as it stands.
 *
 * <p>The home is an {@link IsolatedHome} whose catalogue is left at its origin on github.com, and
 * whose {@code [network] proxy} is a {@link RecordingProxy}, which refuses every connection: the
 * catalogue's clone fails, and so would any download; the test itself runs {@code --offline},
 * against the repository trees the tier's store holds. Those trees are links into the host's test
 * pool, which may be cold, so the class first builds the same template once in the tier's own home,
 * with the network, and so puts what the app's build needs into them.
 */
@Tag("integration")
class NewOfflineFreshHomeTest {

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    /**
     * Scaffolds {@code app} in the tier's home, whose catalogue is the checkout's {@code templates/}
     * and so the same template jk ships, and runs its tests there with the network: Error Prone,
     * NullAway, JSpecify, the JUnit artifacts and their version lists land in the tier's store, where
     * the offline home reads them.
     */
    @BeforeAll
    static void fetchTheAppBuildIntoTheTierStore(@TempDir Path dir) {
        Path warm = dir.resolve("warm");
        jk("new", warm.toString());
        jk("-C", warm.toString(), "test");
    }

    @Test
    @IsolatedHome(officialCatalogFromOrigin = true)
    void with_no_catalogue_and_no_network_jk_new_scaffolds_the_shipped_app_and_it_tests_green(@TempDir Path dir)
            throws Exception {
        Path config = JkDirs.userConfigFile();
        Files.writeString(config, Files.readString(config) + """

                [network]
                proxy = "%s"
                """.formatted(proxy.url()));
        shareTierRepositories();
        Path demo = dir.resolve("demo");

        jk("new", demo.toString());

        assertThat(demo.resolve("jk.toml")).isRegularFile();
        try (Stream<Path> main = Files.walk(demo.resolve("src/main/java"))) {
            assertThat(main.filter(f -> f.endsWith("Main.java")).toList())
                    .as("the java app template's main class")
                    .hasSize(1);
        }
        assertThat(catalogueClones()).as("no catalogue clone in the store").isZero();

        jk("-C", demo.toString(), "--offline", "test");
    }

    /**
     * The home's store reads the tier's repository trees and version lists, as every sandbox store
     * does ({@code TestEnv}): what the project's test needs is on disk ({@link
     * #fetchTheAppBuildIntoTheTierStore}), so it resolves offline. The catalogue clone the tier's
     * store may hold is not shared.
     */
    private static void shareTierRepositories() throws IOException {
        Path tier = Path.of(Objects.requireNonNull(System.getenv("JK_STORE_DIR"), "the tier's store"));
        Path store = JkDirs.store();
        PathUtil.copyTree(tier.resolve("metadata"), store.resolve("metadata"));
        Path repos = Files.createDirectories(store.resolve("repos"));
        PathUtil.forEachChild(tier.resolve("repos"), (tree, attrs) -> {
            Symlinks.create(repos.resolve(String.valueOf(tree.getFileName())), tree.toRealPath());
            return true;
        });
    }

    /** Clones of a catalogue in the home's templates directory. */
    private static long catalogueClones() throws IOException {
        Path templates = JkDirs.templates();
        if (!Files.isDirectory(templates)) return 0;
        try (Stream<Path> clones = Files.list(templates)) {
            return clones.filter(c -> Files.isDirectory(c.resolve("java"))).count();
        }
    }

    /** One in-process jk command, with no terminal; a non-zero exit fails with its console. */
    private static void jk(String... args) {
        String[] all = Stream.concat(Stream.of("--no-ansi", "--no-progress"), Stream.of(args))
                .toArray(String[]::new);
        int[] exit = {0};
        Capture.Streams console = Capture.both(() -> exit[0] = Jk.execute(all));
        assertThat(exit[0])
                .as("jk %s exited %d%n%s%s", String.join(" ", args), exit[0], console.out(), console.err())
                .isZero();
    }
}
