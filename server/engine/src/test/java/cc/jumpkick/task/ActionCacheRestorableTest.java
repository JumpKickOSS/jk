// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hit is a record whose restore would succeed: the forecast and every step's cache check ask
 * {@link ActionCache#restorable} so a prediction never calls a hit the restore then refuses.
 */
class ActionCacheRestorableTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_record_with_its_payload_is_restorable_and_a_pruned_payload_is_a_miss(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Cas cas = new Cas(tmp.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        Path source = tmp.resolve("A.class");
        Files.writeString(source, "class-bytes");
        String sha = Hashing.sha256Hex(source);
        cas.putFile(source, sha);
        ac.storeWithOutputs("compile-main", "key", Map.of(), Map.of("A.class", sha, "marker", "not-a-blob"));

        assertThat(ac.restorable("key")).as("record and payload present").isPresent();

        Files.delete(cas.pathFor(sha));
        assertThat(ac.lookup("key")).as("the record outlives its payload").isPresent();
        assertThat(ac.restorable("key"))
                .as("a pruned payload compiles, it does not restore")
                .isEmpty();
        assertThat(ac.restorable(null)).isEmpty();
        assertThat(ac.restorable("absent")).isEmpty();
    }

    @Test
    void a_payload_promoted_to_the_store_cas_is_restorable(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Cas cas = new Cas(tmp.resolve("cas"));
        Cas store = new Cas(tmp.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"), store);
        Path source = tmp.resolve("B.class");
        Files.writeString(source, "b-bytes");
        String sha = Hashing.sha256Hex(source);
        store.putFile(source, sha);
        ac.storeWithOutputs("compile-main", "key", Map.of(), Map.of("B.class", sha));

        assertThat(ac.restorable("key"))
                .as("the restore finds it in the store CAS")
                .isPresent();
    }
}
