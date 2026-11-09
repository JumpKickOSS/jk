// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prune's memory follows the blobs a cache holds, not the references its key records make to
 * them. Successive generations of a compile key name the same per-file outputs, so a real cache
 * makes tens of references per blob; here a quarter million references to a thousand blobs are
 * pruned inside a 24 MiB heap, where a string per reference would need well over that.
 */
class ActionCachePruneHeapTest {

    private static final int BLOBS = 1_000;
    private static final int KEYS = 250;

    @Test
    void a_quarter_million_output_references_are_pruned_inside_a_24_mib_heap(@TempDir Path root) throws Exception {
        seed(root);
        Path log = root.resolve("fork.log");
        List<String> cmd = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx24m",
                "-XX:+UseSerialGC",
                "-cp",
                System.getProperty("java.class.path"),
                ActionCachePruneHeapTest.class.getName(),
                root.toString());
        Process fork = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean done = fork.waitFor(2, TimeUnit.MINUTES);
        if (!done) fork.destroyForcibly();
        String out = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(done).as("fork finished: " + out).isTrue();
        assertThat(fork.exitValue()).as(out).isZero();
        // The stale half goes by the window; every blob is still named by the recent half.
        assertThat(out.strip()).isEqualTo("keys " + KEYS / 2 + " blobs 0");
    }

    /** The forked side: one real pass over the cache at {@code args[0]}, its counts printed. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        var policy = new ActionCachePrune.Policy(
                0L,
                ActionCachePrune.Policy.ACTION_WINDOW,
                ActionCachePrune.Policy.MEMO_WINDOW,
                ActionCachePrune.Policy.MEMO_MAX_ENTRIES,
                0L,
                ActionCachePrune.Policy.INCREMENTAL_WINDOW);
        var report = ActionCachePrune.run(root, new Cas(root), policy, Set.of(), false);
        System.out.println("keys " + report.deletedKeys() + " blobs " + report.deletedBlobs());
    }

    /** {@link #BLOBS} small blobs, and {@link #KEYS} records that each name all of them; half are stale. */
    private static void seed(Path root) throws IOException {
        Cas cas = new Cas(root);
        StringBuilder outputs = new StringBuilder();
        for (int i = 0; i < BLOBS; i++) {
            byte[] payload = ("refs of Source" + i).getBytes(StandardCharsets.UTF_8);
            backdate(cas.put(payload), Duration.ofDays(1));
            outputs.append("OUTPUT ")
                    .append(Hashing.sha256Hex(payload))
                    .append(" src/main/java/com/example/generated/Source")
                    .append(i)
                    .append(".java.refs\n");
        }
        Path keys = Files.createDirectories(root.resolve("actions").resolve("keys"));
        Path tasks = Files.createDirectories(root.resolve("actions").resolve("tasks"));
        for (int k = 0; k < KEYS; k++) {
            String actionKey = "key-" + k;
            String taskId = JavaCompile.referencesTaskId("compile-main@" + k);
            Path keyFile = keys.resolve(actionKey);
            Files.writeString(
                    keyFile, "TASK " + taskId + "\nKEY " + actionKey + "\n" + outputs, StandardCharsets.UTF_8);
            Files.writeString(tasks.resolve(taskId), actionKey, StandardCharsets.UTF_8);
            backdate(keyFile, k % 2 == 0 ? Duration.ofDays(60) : Duration.ofDays(1));
        }
    }

    private static void backdate(Path file, Duration age) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - age.toMillis()));
    }
}
