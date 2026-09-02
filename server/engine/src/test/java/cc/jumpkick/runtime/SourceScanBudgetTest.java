// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.model.Project;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guard G44: after {@code coverModule}, Languages + collect + fingerprint enumerate {@code src/}
 * once.
 */
class SourceScanBudgetTest {

    @AfterEach
    void clear() {
        InputTrees.resetForTest();
        RequestScope.clearAll();
        SessionContext.reset();
        PathUtil.resetWalks();
    }

    @Test
    void cover_then_languages_collect_fingerprint_walk_src_once(@TempDir Path dir) throws Exception {
        Path module = Files.createDirectories(dir.resolve("m"));
        Path src = Files.createDirectories(module.resolve("src/main/java"));
        for (int i = 0; i < 8; i++) {
            Files.writeString(src.resolve("A" + i + ".java"), "class A" + i + " {}");
        }
        Project project = Project.builder("com.example", "m", "1.0.0").build();

        inRequest(() -> {
            PathUtil.resetWalks();
            InputTrees.coverModule(module);
            Languages.resolve(project, module);
            collect(() -> CompileSupport.collectJavaSources(src));
            collect(() -> CompileSupport.collectKotlinSources(module, false));
            collect(() -> CompileSupport.collectGroovySources(module, false));
            PreflightMemo.fingerprintModule(module, true);
            assertThat(PathUtil.walks()).as("one covering walk of src/").isEqualTo(1);
        });
    }

    @Test
    void asking_the_same_language_twice_scans_once(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("A.java"), "class A {}");

        inRequest(() -> {
            // Walk count, not scope size: scope size never shrinks, so any >= there pins nothing.
            PathUtil.resetWalks();
            for (int i = 0; i < 21; i++) {
                collect(() -> CompileSupport.collectJavaSources(src));
            }
            assertThat(PathUtil.walks()).as("21 asks, one scan").isEqualTo(1);
        });
    }

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
            InputTrees.finishJob();
            IoLedger.close();
        }
    }
}
