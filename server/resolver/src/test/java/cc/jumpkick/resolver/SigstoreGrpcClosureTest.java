// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sigstore-java's non-optional runtime dep {@code grpc-netty-shaded} (and its closure —
 * grpc-core, com.google.android:annotations, perfmark) must survive the lock. The b1a1e7d9 re-lock
 * silently dropped the whole subtree: an unresolvable transitive must fail the solve loudly, never
 * vanish.
 */
@Tag("network")
class SigstoreGrpcClosureTest {

    @Test
    void sigstore_lock_keeps_grpc_netty_shaded_closure(@TempDir Path tmp) throws Exception {
        checkClosure(tmp, false);
    }

    @Test
    void sigstore_lock_keeps_closure_with_google_exclusive_repo(@TempDir Path tmp) throws Exception {
        // The product repo group: central + Google Android Maven with its exclusive groups. The
        // bare com.google.android group must NOT be exclusively claimed — its lone artifact
        // (annotations, a grpc-core dep) lives only on Central.
        checkClosure(tmp, true);
    }

    private static void checkClosure(Path tmp, boolean withGoogle) throws Exception {
        Cas cas = new Cas(tmp.resolve("c"));
        Http http = new Http();
        MavenRepo central = new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), http, cas);
        RepoGroup repos;
        if (withGoogle) {
            MavenRepo google =
                    new MavenRepo("google", URI.create("https://dl.google.com/dl/android/maven2/"), http, cas);
            repos = new RepoGroup(
                    List.of(central, google), null, List.of(List.of(), RepositorySpec.GOOGLE_ANDROID_GROUPS));
        } else {
            repos = RepoGroup.of(central);
        }
        EnumMap<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        by.put(Scope.MAIN, List.of(new Dependency("dev.sigstore:sigstore-java", VersionSelector.parse("2.2.0"))));
        JkBuild project = new JkBuild(new Project("com.example", "demo", "0.1.0", 25), new JkBuild.Dependencies(by));
        Lockfile lock = new LockOrchestrator(repos).lock(project, "test");

        Lockfile.Artifact sigstore = lock.artifacts().stream()
                .filter(a -> a.packageKey().startsWith("dev.sigstore:sigstore-java"))
                .findFirst()
                .orElseThrow();
        assertThat(sigstore.deps().stream().anyMatch(d -> d.contains("grpc-netty-shaded")))
                .as("sigstore-java must list grpc-netty-shaded: %s", sigstore.deps())
                .isTrue();
        assertThat(lock.artifacts().stream().anyMatch(a -> a.name().contains("grpc-netty-shaded")))
                .isTrue();
        assertThat(lock.artifacts().stream().anyMatch(a -> a.packageKey().startsWith("com.google.android:annotations")))
                .as("netty-shaded's transitive annotations dep must resolve (Central hosts it)")
                .isTrue();
    }
}
