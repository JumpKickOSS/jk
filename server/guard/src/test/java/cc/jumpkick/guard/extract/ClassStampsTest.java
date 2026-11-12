// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.guard.extract.FactsIndexing.Ensured;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassStampsTest {

    /**
     * The forecast's probe, the lane's ensure and the workspace tokens ask about one classes tree
     * many times in a request; it is walked once until a step ends, and walked again after.
     */
    @Test
    void aTreeIsWalkedOncePerRequestUntilAStepEnds(@TempDir Path dir) throws IOException {
        Path pkg = dir.resolve("classes/cc/jumpkick/guard/extract/fixture");
        Files.createDirectories(pkg);
        Path sample = pkg.resolve("Sample.class");
        Files.write(sample, FixtureBytes.of(Sample.class));
        Path classes = dir.resolve("classes");
        Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");
        IoLedger ledger = new IoLedger();
        Request.of(1, ledger).run(() -> {
            try {
                Ensured built = FactsIndexing.ensure(classes, idx);
                assertThat(FactsIndexing.freshDigest(classes, idx)).contains(built.bodyDigest());
                assertThat(FactsIndexing.freshDigest(classes, idx)).contains(built.bodyDigest());
                assertThat(FactsIndexing.ensure(classes, idx).tier()).isEqualTo(Ensured.Tier.FRESH);
                assertThat(ClassStamps.memo().walks()).isEqualTo(1);

                // A step rewrites the class while it runs; the next ask after it ends sees the change.
                Files.setLastModifiedTime(
                        sample,
                        FileTime.fromMillis(Files.getLastModifiedTime(sample).toMillis() + 5_000));
                StepScope.run(step(), () -> {});
                assertThat(FactsIndexing.freshDigest(classes, idx)).isEmpty();
                assertThat(ClassStamps.memo().walks()).isEqualTo(2);
            } finally {
                RequestScope.release(ledger);
            }
        });
    }

    /** A step that does nothing; only its scope's close matters here. */
    private static TaskContext step() {
        return (TaskContext) Proxy.newProxyInstance(
                TaskContext.class.getClassLoader(),
                new Class<?>[] {TaskContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "step";
                    case "cancelled" -> false;
                    default -> null;
                });
    }
}
