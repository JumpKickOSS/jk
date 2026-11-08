// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A declared repository that accepts every request and never answers, ahead of the one that serves
 * the graph: the lock waits out one timeout, asks it nothing more, and resolves every package from
 * the other repository, a subtree whose POM declares a repository of its own included.
 */
class LockSilentRepositoryTest {

    /** Where the repository says it is: no loopback address, so nothing makes it optional. */
    private static final URI GITLAB = URI.create("https://gitlab.example/api/v4/packages/maven/");

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp vendor = new LoopbackHttp().concurrent();

    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> notices = Collections.synchronizedList(new ArrayList<>());
    private HttpServer silent;
    private URI silentBase;

    @BeforeEach
    void publish() throws IOException {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        RunNotices.clear();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));
        silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.setExecutor(Executors.newCachedThreadPool());
        silent.createContext("/", exchange -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        silent.start();
        silentBase = URI.create("http://127.0.0.1:" + silent.getAddress().getPort() + "/maven/");
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .metadata("com.foo", "app-lib", "1.0")
                .pom("com.foo", "app-lib", "1.0", """
                        <project>
                          <groupId>com.foo</groupId>
                          <artifactId>app-lib</artifactId>
                          <version>1.0</version>
                          <repositories>
                            <repository>
                              <id>vendor</id>
                              <url>%s</url>
                            </repository>
                          </repositories>
                          <dependencies>
                            <dependency>
                              <groupId>com.foo</groupId>
                              <artifactId>b</artifactId>
                              <version>1.0</version>
                            </dependency>
                            <dependency>
                              <groupId>com.foo</groupId>
                              <artifactId>c</artifactId>
                              <version>1.0</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """.formatted(vendor.baseUrl()))
                .jar("com.foo", "app-lib", "1.0")
                .leaf("com.foo", "b", "1.0")
                .leaf("com.foo", "c", "1.0")
                .leaf("com.foo", "d", "1.0");
    }

    @AfterEach
    void stop() {
        release.countDown();
        silent.stop(0);
        RunNotices.clear();
    }

    @Test
    @Timeout(60)
    void a_repository_that_never_answers_costs_one_timeout_and_the_rest_resolve_elsewhere(@TempDir Path dir)
            throws Exception {
        Cas cas = new Cas(dir.resolve("cache"));
        Rerouted gitlabTransport = new Rerouted();
        MavenRepo gitlab = MavenRepo.overTransport(
                "gitlab", GITLAB, gitlabTransport, cas, RepoCredential.ANONYMOUS, null, false, false, false);
        RepoGroup repos = new RepoGroup(List.of(gitlab, new MavenRepo("central", central.base(), new Http(), cas)));

        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:app-lib", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:d", VersionSelector.parse("=1.0"))));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));

        Lockfile lock = new LockOrchestrator(repos).lock(project, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .anyMatch(n -> n.startsWith("com.foo:app-lib:"))
                .anyMatch(n -> n.startsWith("com.foo:b:"))
                .anyMatch(n -> n.startsWith("com.foo:c:"))
                .anyMatch(n -> n.startsWith("com.foo:d:"));
        assertThat(lock.artifacts())
                .as("every row came from the repository that answered")
                .allSatisfy(a -> assertThat(a.source()).doesNotContain("gitlab"));
        assertThat(gitlabTransport.asked.get())
                .as("the silent repository was asked")
                .isPositive();
        assertThat(gitlabTransport.askedAfterSilence.get())
                .as("no request was sent to it once one had gone unanswered")
                .isZero();
        assertThat(notices)
                .as("said once")
                .filteredOn(n -> n.contains("gitlab"))
                .singleElement()
                .asString()
                .contains("skipped for the rest of this lock");
    }

    /**
     * Sends every request for {@link #GITLAB} to the silent server, under a short request timeout,
     * and counts the requests sent after one already went unanswered.
     */
    private final class Rerouted implements RepoTransport {
        final AtomicInteger asked = new AtomicInteger();
        final AtomicInteger askedAfterSilence = new AtomicInteger();
        private final AtomicBoolean silenced = new AtomicBoolean();
        private final RepoTransport to =
                RepoTransports.forUrl(silentBase, new Http().withRequestTimeout(Duration.ofMillis(500)));

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
            return counted(() -> to.fetch(at(uri), credential));
        }

        @Override
        public Optional<InputStream> fetchStream(URI uri, RepoCredential credential)
                throws IOException, InterruptedException {
            return counted(() -> to.fetchStream(at(uri), credential));
        }

        @Override
        public Optional<Body> fetchBody(URI uri, RepoCredential credential) throws IOException, InterruptedException {
            return counted(() -> to.fetchBody(at(uri), credential));
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
            throw new UnsupportedOperationException();
        }

        private URI at(URI uri) {
            return silentBase.resolve(GITLAB.relativize(uri));
        }

        private <T> T counted(Request<T> request) throws IOException, InterruptedException {
            asked.incrementAndGet();
            if (silenced.get()) askedAfterSilence.incrementAndGet();
            try {
                return request.send();
            } catch (IOException e) {
                silenced.set(true);
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface Request<T> {
        T send() throws IOException, InterruptedException;
    }
}
