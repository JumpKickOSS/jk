// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.testing.ShortTempDirs;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The election rules, without an engine: startup mutex, the same-version/same-build incumbent
 * probe, the generation claim, the endpoint pointer and its retirement.
 *
 * <p>These bind a Unix-domain socket in a temp directory and immediately release it — file and
 * lock bookkeeping, no accept loop, no threads of the engine's — so they belong in the fast tier
 * rather than behind {@code @Tag("integration")}.
 */
class EngineElectionTest {

    private static final String VERSION = "9.9.9-test";

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jke-");

    private final List<AutoCloseable> openables = new ArrayList<>();

    private <T extends AutoCloseable> T closeLater(T c) {
        openables.add(c);
        return c;
    }

    @AfterEach
    void cleanup() {
        for (AutoCloseable c : openables) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private EngineElection election(EnginePaths.Paths p, String buildId, long pid) {
        return new EngineElection(p, VERSION, buildId, pid, 1_700_000_000_000L, s -> {});
    }

    /**
     * A live engine that answers one {@code hello} and nothing else: it holds a generation's lock
     * and socket the way a serving engine does, so the election under test sees a real incumbent
     * without an {@link EngineServer} behind it.
     */
    private final class FakeIncumbent implements AutoCloseable {
        private final ServerSocketChannel listener;
        private final FileChannel lockChannel;
        private final FileLock lock;
        private final Thread thread;

        FakeIncumbent(EnginePaths.Paths paths, int generation, String version, String buildId) throws IOException {
            EnginePaths.Paths gen = EnginePaths.generation(paths, generation);
            Files.createDirectories(gen.dir());
            this.lockChannel = FileChannel.open(gen.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            this.lock = lockChannel.tryLock();
            this.listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            this.listener.bind(UnixDomainSocketAddress.of(gen.socket()));
            Files.writeString(gen.pid(), "424242\n1\n", StandardCharsets.UTF_8);
            EnginePaths.writeEndpoint(paths, gen.socket());
            this.thread = new Thread(
                    () -> {
                        while (listener.isOpen()) {
                            try (SocketChannel ch = listener.accept()) {
                                BufferedReader r = new BufferedReader(
                                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                                BufferedWriter w = new BufferedWriter(
                                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                                if (r.readLine() == null) continue;
                                w.write(ProtoLifecycle.helloAck(version, 424242L, 1L, false, buildId));
                                w.write('\n');
                                w.flush();
                            } catch (IOException e) {
                                return; // listener closed
                            }
                        }
                    },
                    "fake-incumbent");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void close() throws IOException {
            listener.close();
            if (lock != null) lock.release();
            lockChannel.close();
        }
    }

    @Test
    void winning_claims_the_first_generation_and_points_the_endpoint_at_it() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);

        EngineElection.Won won = e.win();

        assertThat(won).isNotNull();
        closeLater(won.listener());
        EnginePaths.Paths gen1 = EnginePaths.generation(p, 1);
        assertThat(won.active().socket()).isEqualTo(gen1.socket());
        assertThat(won.displaced()).as("nothing was live before us").isNull();
        assertThat(gen1.socket()).exists();
        assertThat(Files.readString(gen1.pid())).startsWith("4242\n");
        assertThat(Files.readString(EnginePaths.endpoint(p)).trim())
                .isEqualTo(gen1.socket().getFileName().toString());
        assertThat(e.endpointNamesThisEngine()).isTrue();
        assertThat(e.endpointMissing()).isFalse();
        assertThat(e.displacedBySuccessor()).isFalse();
    }

    @Test
    void the_startup_mutex_refuses_a_spawn_that_arrives_mid_election() throws Exception {
        Path state = tempDirs.create();
        EnginePaths.Paths p = EnginePaths.resolve(state);
        Files.createDirectories(p.dir());
        // Stand in for another spawn that is between probe and endpoint write.
        FileChannel held = closeLater(FileChannel.open(p.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE));
        assertThat(held.tryLock()).isNotNull();

        assertThat(election(p, "aaaa", 4242).win()).isNull();
        assertThat(EnginePaths.endpoint(p))
                .as("a loser touches nothing but the lock file")
                .doesNotExist();
    }

    @Test
    void an_incumbent_of_the_same_version_and_build_wins_and_the_newcomer_loses_quietly() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        closeLater(new FakeIncumbent(p, 1, VERSION, "aaaa"));

        assertThat(election(p, "aaaa", 4242).win())
                .as("a redundant spawn-race participant")
                .isNull();
        assertThat(Files.readString(EnginePaths.endpoint(p)).trim())
                .as("the incumbent still owns the endpoint")
                .isEqualTo(EnginePaths.generation(p, 1).socket().getFileName().toString());
    }

    /**
     * A rebuilt -SNAPSHOT engine is the same version string with different content. Treating that
     * as "already serving" is how a stale dev engine kept serving old code, so a differing buildId
     * must take over — into the next free generation, since the incumbent still holds gen 1.
     */
    @Test
    void a_different_build_id_takes_over_into_the_next_generation() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        closeLater(new FakeIncumbent(p, 1, VERSION, "aaaa"));

        EngineElection.Won won = election(p, "bbbb", 4242).win();

        assertThat(won).isNotNull();
        closeLater(won.listener());
        assertThat(won.active().socket()).isEqualTo(EnginePaths.generation(p, 2).socket());
        assertThat(won.displaced())
                .as("the predecessor to ask to yield")
                .isEqualTo(EnginePaths.generation(p, 1).socket());
        assertThat(Files.readString(EnginePaths.endpoint(p)).trim())
                .as("takeover repoints the endpoint")
                .isEqualTo(EnginePaths.generation(p, 2).socket().getFileName().toString());
    }

    @Test
    void a_crashed_generations_leftover_socket_file_is_reclaimed_not_a_bind_failure() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EnginePaths.Paths gen1 = EnginePaths.generation(p, 1);
        Files.createDirectories(gen1.dir());
        // A killed engine leaves its socket and token behind; its lock, though, is free.
        Files.writeString(gen1.socket(), "stale");
        Files.writeString(gen1.token(), "stale");

        EngineElection.Won won = election(p, "aaaa", 4242).win();

        assertThat(won).isNotNull();
        closeLater(won.listener());
        assertThat(won.active().socket()).isEqualTo(gen1.socket());
        assertThat(won.listener().isOpen()).isTrue();
    }

    @Test
    void an_endpoint_naming_another_generation_reads_as_displaced() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);
        closeLater(e.win().listener());

        Files.writeString(EnginePaths.endpoint(p), p.key() + ".gen999.sock");

        assertThat(e.displacedBySuccessor()).isTrue();
        assertThat(e.endpointNamesThisEngine()).isFalse();
    }

    /** The filename alone is not identity: a recreated state dir reuses the generation name. */
    @Test
    void a_pid_file_naming_another_process_reads_as_displaced() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);
        EngineElection.Won won = e.win();
        closeLater(won.listener());

        Files.writeString(won.active().pid(), "1\n");

        assertThat(e.displacedBySuccessor()).isTrue();
    }

    @Test
    void a_missing_endpoint_is_orphaned_not_displaced() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);
        closeLater(e.win().listener());

        Files.delete(EnginePaths.endpoint(p));

        assertThat(e.endpointMissing()).isTrue();
        assertThat(e.displacedBySuccessor())
                .as("nobody else claimed the name — this engine is orphaned, not displaced")
                .isFalse();
    }

    @Test
    void retiring_drops_this_generations_files_and_the_endpoint_it_owns() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);
        EngineElection.Won won = e.win();
        won.listener().close();

        e.retire();

        assertThat(won.active().socket()).doesNotExist();
        assertThat(won.active().pid()).doesNotExist();
        assertThat(won.active().lock()).doesNotExist();
        assertThat(EnginePaths.endpoint(p)).doesNotExist();
        assertThat(p.lock()).as("the transient startup mutex file").doesNotExist();
    }

    /** A lame duck must not un-point an endpoint its successor now owns. */
    @Test
    void retiring_leaves_a_successors_endpoint_alone() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection e = election(p, "aaaa", 4242);
        EngineElection.Won won = e.win();
        won.listener().close();
        Files.writeString(EnginePaths.endpoint(p), p.key() + ".gen7.sock");

        e.retire();

        assertThat(won.active().socket()).doesNotExist();
        assertThat(Files.readString(EnginePaths.endpoint(p)).trim()).isEqualTo(p.key() + ".gen7.sock");
    }
}
