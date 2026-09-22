// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.objects;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.jdk.JdkRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpMachineMutateTest {

    @Test
    void disk_nuke_without_confirm_is_preview(@TempDir Path cache) throws Exception {
        Path key = seed(cache.resolve("actions/keys/task1"));
        Map<String, Object> preview = McpMachine.diskAction("nuke", false, cache, null);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(key).exists();
        Map<String, Object> done = McpMachine.diskAction("nuke", true, cache, null);
        assertThat(done.get("nuked")).isEqualTo(true);
        assertThat(key).doesNotExist();
        // A confirmed nuke empties the root, tier table or no tier table. The artifact store is
        // safe because it is never under this root (JkStores resolves it from JK_STORE_DIR), not
        // because a leftover repos/ here is spared — sparing it only left residue a plain
        // `jk cache clean` would have reclaimed.
        Path leftover = seed(cache.resolve("repos/central/lib.jar"));
        McpMachine.diskAction("nuke", true, cache, null);
        assertThat(leftover).doesNotExist();
    }

    /**
     * A cache action prices the tier it touches and nothing else. {@code clean} and {@code nuke}
     * leave the artifact store alone, so reporting its size here bought a full walk of a tree with
     * no upper bound on every call, preview included. The store's path still rides along: naming
     * the tier an action does not touch is the useful half. {@code usage} still reports
     * {@code storeBytes}, off the memoized snapshot — see {@code McpDiskTest}.
     */
    @Test
    void disk_actions_price_the_cache_tier_and_never_walk_the_store(@TempDir Path cache) throws Exception {
        seed(cache.resolve("actions/keys/task1"));
        for (Map<String, Object> reply : List.of(
                McpMachine.diskAction("nuke", false, cache, null),
                McpMachine.diskAction("clean", false, cache, null),
                McpMachine.diskAction("clean", true, cache, null))) {
            assertThat(reply).containsKey("cacheBytes").containsKey("storeDir");
            assertThat(reply)
                    .as("storeBytes is the store walk, and nothing here touches the store")
                    .doesNotContainKey("storeBytes");
            assertThat(reply.get("error")).isNull();
        }
    }

    @Test
    void disk_clean_without_confirm_is_preview(@TempDir Path cache) {
        Map<String, Object> preview = McpMachine.diskAction("clean", false, cache, null);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(String.valueOf(preview.get("note"))).contains("confirm=true");
    }

    @Test
    void disk_mutations_refuse_while_a_build_holds_the_cache_gate(@TempDir Path cache) throws Exception {
        var gate = new ReentrantReadWriteLock(true);
        Path key = seed(cache.resolve("actions/keys/task1"));
        gate.readLock().lock(); // an in-flight plan holds the read side for its whole run
        try {
            Map<String, Object> busy = McpMachine.diskAction("nuke", true, cache, gate);
            assertThat(String.valueOf(busy.get("error"))).contains("busy");
            assertThat(busy.get("nuked")).isNull();
            assertThat(key).exists();
        } finally {
            gate.readLock().unlock();
        }
        Map<String, Object> done = McpMachine.diskAction("nuke", true, cache, gate);
        assertThat(done.get("nuked")).isEqualTo(true);
        assertThat(key).doesNotExist();
    }

    @Test
    void disk_clean_stamps_last_pruned(@TempDir Path cache) {
        Map<String, Object> done = McpMachine.diskAction("clean", true, cache, null);
        assertThat(done.get("cleaned")).isEqualTo(true);
        assertThat(cache.resolve(".last-pruned")).exists();
    }

    @Test
    void jdk_uninstall_requires_confirm(@TempDir Path jdks) throws Exception {
        Path home = fakeJdk(jdks, "temurin-21.0.5", "21.0.5");
        JdkRegistry registry = new JdkRegistry(jdks);
        Map<String, Object> preview = McpMachine.jdkAction("uninstall", "21", null, false, registry);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(home).exists();
        Map<String, Object> done = McpMachine.jdkAction("uninstall", "21", null, true, registry);
        assertThat(done.get("removed")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> removed = (List<String>) done.get("removed");
        assertThat(removed).isNotEmpty();
        assertThat(home).doesNotExist();
    }

    @Test
    void jdk_uninstall_older_than_previews_then_removes(@TempDir Path jdks) throws Exception {
        Path old = fakeJdk(jdks, "temurin-21.0.5", "21.0.5");
        Path keep = fakeJdk(jdks, "temurin-25.0.1", "25.0.1");
        JdkRegistry registry = new JdkRegistry(jdks);
        Map<String, Object> preview = McpMachine.jdkAction("uninstall", null, 25, false, registry);
        assertThat(preview.get("preview")).isEqualTo(true);
        List<Map<String, Object>> victims = objects(preview, "victims");
        assertThat(victims).isNotEmpty();
        assertThat(victims.stream().map(v -> String.valueOf(v.get("identifier"))))
                .anyMatch(id -> id.contains("21"));
        McpMachine.jdkAction("uninstall", null, 25, true, registry);
        assertThat(old).doesNotExist();
        assertThat(keep).exists();
    }

    @Test
    void jdk_install_requires_spec() {
        Map<String, Object> m = McpMachine.jdkAction("install", "", null, false);
        assertThat(String.valueOf(m.get("error"))).contains("spec");
    }

    @Test
    void major_of_parses_versions() {
        assertThat(McpMachine.majorOf("21.0.5")).isEqualTo(21);
        assertThat(McpMachine.majorOf("temurin-25.0.1")).isEqualTo(25);
        assertThat(McpMachine.majorOf("25")).isEqualTo(25);
    }

    private static Path fakeJdk(Path jdks, String folder, String version) throws Exception {
        Path home = jdks.resolve(folder);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake\n");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return home;
    }

    private static Path seed(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[32]);
        return file;
    }
}
