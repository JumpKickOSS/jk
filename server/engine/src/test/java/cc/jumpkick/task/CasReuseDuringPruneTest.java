// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second engine sharing the cache root holds none of the prune's locks. When it stores a new
 * record over a blob the CAS already holds, after the prune scanned the keys, the prune must not
 * then unlink that blob with the old key it evicts.
 */
class CasReuseDuringPruneTest {

    @Test
    void a_blob_another_engine_reuses_mid_prune_survives_the_old_key(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        long old = System.currentTimeMillis() - Duration.ofDays(60).toMillis();
        byte[] payload = "class bytes".getBytes(StandardCharsets.UTF_8);
        Path blob = cas.put(payload);
        String sha = Hashing.sha256Hex(payload);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(old));
        Path oldKey = root.resolve("actions/keys/key-old");
        Files.createDirectories(oldKey.getParent());
        Files.writeString(
                oldKey,
                ActionRecordText.render("compile-main@a", "key-old", Map.of(), Map.of("A.class", sha), Set.of()),
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(oldKey, FileTime.fromMillis(old));

        Path out = Files.createDirectories(root.resolve("other-checkout/classes"));
        Files.write(out.resolve("A.class"), payload);
        ActionCache otherEngine = new ActionCache(cas, root.resolve("actions"));
        AtomicBoolean stored = new AtomicBoolean();
        // The prune's safe point before each eviction: the other engine stores there, after the scan.
        PruneYield interleave = new PruneYield(() -> {
            boolean evicting = StackWalker.getInstance()
                    .walk(s -> s.anyMatch(f -> f.getMethodName().equals("evict")));
            if (evicting && stored.compareAndSet(false, true)) {
                try {
                    otherEngine.store("compile-main@b", "key-new", Map.of(), out);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return false;
        });

        ActionCachePrune.run(
                root,
                cas,
                new ActionCachePrune.Policy(1L, Duration.ZERO, Duration.ZERO, 0, 0L, Duration.ZERO),
                Set.of(),
                false,
                interleave);

        assertThat(stored).as("the store ran inside the prune").isTrue();
        assertThat(root.resolve("actions/keys/key-new")).exists();
        assertThat(cas.pathFor(sha))
                .as("the blob key-new names, reused a moment ago")
                .exists();
        assertThat(otherEngine.restorable("key-new")).isPresent();
    }
}
