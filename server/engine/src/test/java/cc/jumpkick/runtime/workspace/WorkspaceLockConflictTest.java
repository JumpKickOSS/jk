// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** A workspace's own lock freshen that the solver refuses keeps the conflict behind its reason. */
class WorkspaceLockConflictTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void start() {
        // The product store is ambient; a test that resolves keeps its metadata out of the real one.
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.leaf("com.foo", "lib", "1.0");
    }

    @Test
    void a_freshen_with_no_solution_carries_its_conflict_as_one_object(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "=9.9" }
                """);

        BuildService.LockGuard guard =
                WorkspaceLock.ensureWorkspaceLockFresh(tmp, tmp.resolve("cache"), true, http.base());

        assertThat(guard.status()).isEqualTo(Exit.RESOLVE);
        assertThat(guard.error()).contains("com.foo:lib");
        Map<?, ?> conflict = (Map<?, ?>) requireNonNull(MiniJson.parse(guard.conflict()));
        assertThat(conflict.get("facts")).asInstanceOf(LIST).anySatisfy(fact -> assertThat(
                        ((Map<?, ?>) fact).get("package"))
                .isEqualTo("com.foo:lib"));
    }

    @Test
    void a_fresh_lock_has_no_conflict() {
        assertThat(BuildService.LockGuard.OK.conflict()).isEmpty();
    }
}
