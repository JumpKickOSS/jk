// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A package's candidate list is read once however many callers ask for it: the solver's own decide
 * and the warm-up running ahead of it ask for the same package, and a loopback repository's catalog
 * is never trusted from disk, so a second read is a second request.
 */
class MavenPackageSourceCandidateReadsTest {

    private static final String LIB = "com.foo:lib:jar:";
    private static final String LIB_META = MavenStub.metadataPath("com.foo", "lib");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @BeforeEach
    void clear() {
        ResolveProcessCacheControl.clearAll();
        SessionContext.reset();
        new MavenStub(http).metadata("com.foo", "lib", "1.0", "2.0");
    }

    @AfterEach
    void reset() {
        MavenPackageSource.candidatesMissed = () -> {};
        SessionContext.reset();
    }

    @Test
    void a_list_another_caller_reads_and_lands_after_this_one_looked_is_taken_not_read_again(@TempDir Path tmp)
            throws Exception {
        MavenPackageSource src = source(tmp);
        CountDownLatch looked = new CountDownLatch(1);
        CountDownLatch landed = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // The first caller is held between its cache miss and its read until the second caller's
        // read has run to the end and been cached.
        MavenPackageSource.candidatesMissed = () -> {
            if (!first.compareAndSet(true, false)) return;
            looked.countDown();
            try {
                landed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        CompletableFuture<List<String>> held = new CompletableFuture<>();
        Thread.ofPlatform().start(() -> {
            try {
                held.complete(src.versions(LIB));
            } catch (Exception | Error e) {
                held.completeExceptionally(e);
            }
        });
        assertThat(looked.await(10, TimeUnit.SECONDS)).isTrue();
        List<String> read = src.versions(LIB);
        landed.countDown();

        assertThat(held.get(10, TimeUnit.SECONDS)).isEqualTo(read).containsExactly("2.0", "1.0");
        assertThat(http.requested())
                .as("the catalog is asked for once")
                .filteredOn(LIB_META::equals)
                .hasSize(1);
    }

    private MavenPackageSource source(Path tmp) {
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
    }
}
