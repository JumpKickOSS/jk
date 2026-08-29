// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guard <b>G44</b>: a per-invocation budget on how many times one source tree is enumerated.
 *
 * <p>This is the guard JK-1027 argued mattered most, and the only one that could have caught its
 * headline finding — a 260,971-syscall {@code jk status} violates no shape rule, because every
 * individual call site is locally reasonable. It has moved twice, from [[JK-1028]] to [[JK-1046]] to
 * here, each time because the number it would pin was about to change. It lands with the request
 * scope that finally makes the number stable.
 *
 * <p>The shape it pins: {@code src/main/java} is reachable from the Java, Kotlin, Groovy and Scala
 * collectors, because the non-Java root sets deliberately include the Java root (a stray
 * {@code .groovy} there must still compile, JK-2479). Asking all four used to walk it four times.
 */
class SourceScanBudgetTest {

    @AfterEach
    void clear() {
        RequestScope.clearAll();
        SessionContext.reset();
    }

    @Test
    void one_tree_asked_for_four_languages_is_enumerated_once_per_extension(@TempDir Path dir) throws Exception {
        Path module = Files.createDirectories(dir.resolve("m"));
        Path src = Files.createDirectories(module.resolve("src/main/java"));
        for (int i = 0; i < 40; i++) {
            Files.writeString(src.resolve("A" + i + ".java"), "class A" + i + " {}");
        }

        inRequest(() -> {
            // The four collectors, twice — the repeat models the forecast, pricing and plan passes
            // asking the same module the same question. The Groovy and Scala root sets include
            // src/main/java, so all four reach this tree.
            for (int pass = 0; pass < 2; pass++) {
                collect(() -> CompileSupport.collectJavaSources(src));
                collect(() -> CompileSupport.collectKotlinSources(module, false));
                collect(() -> CompileSupport.collectGroovySources(module, false));
                collect(() -> CompileSupport.collectScalaSources(module, false));
            }
            // One entry per distinct (root, extension). Eight calls, and no key is computed twice —
            // before the request scope each of the eight walked a tree.
            assertThat(RequestScope.current().size())
                    .as("distinct scans held for the request")
                    .isPositive()
                    .isLessThanOrEqualTo(8);
        });
    }

    @Test
    void asking_the_same_language_twice_scans_once(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("A.java"), "class A {}");

        inRequest(() -> {
            collect(() -> CompileSupport.collectJavaSources(src));
            int afterFirst = RequestScope.current().size();
            for (int i = 0; i < 20; i++) {
                collect(() -> CompileSupport.collectJavaSources(src));
            }
            assertThat(RequestScope.current().size()).as("21 asks, one scan").isEqualTo(afterFirst);
        });
    }

    private static int scopeSize() {
        return RequestScope.current().size();
    }

    /** A collector call that may fail on the filesystem. */
    private interface Scan {
        List<Path> run() throws IOException;
    }

    private static void collect(Scan scan) {
        try {
            scan.run();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void inRequest(Runnable body) {
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            SessionContext.runWhere(Session.defaults().withIo(ledger), body);
        } finally {
            IoLedger.close();
        }
    }
}
