// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sources jar is zipped straight to its file. The source here does not compress, so the jar is
 * as large as it is, and a jar built in memory first shows in what the writing thread allocates.
 */
class SourcesJarStreamingTest {

    @Test
    void a_large_sources_jar_is_written_without_holding_it_whole(@TempDir Path tmp) throws Exception {
        int size = 16 << 20;
        Path root = Files.createDirectories(tmp.resolve("src/main/java/p"));
        SplittableRandom random = new SplittableRandom(1);
        byte[] chunk = new byte[1 << 16];
        try (OutputStream out = Files.newOutputStream(root.resolve("Blob.java"))) {
            for (int written = 0; written < size; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk);
            }
        }
        Path jar = tmp.resolve("out/p-sources.jar");
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        long before = threads.getCurrentThreadAllocatedBytes();
        SourcesJar.write(List.of(tmp.resolve("src/main/java")), jar);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(Files.size(jar)).isGreaterThan(size);
        assertThat(allocated).isLessThan(size / 4);
    }
}
