// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An action record names its outputs by path relative to the directory a restore writes into. A
 * record that did not come from this engine (a restored CI cache, a future remote cache or cache
 * import) must not reach outside that directory, and must answer for the key it was asked under.
 */
class ActionRecordUntrustedTest {

    private static void plant(Path actions, String fileName, String keyLine, Map<String, String> outputs)
            throws Exception {
        Path key = actions.resolve("keys").resolve(fileName);
        Files.createDirectories(key.getParent());
        Files.writeString(
                key,
                ActionRecordText.render("package-jar@x", keyLine, Map.of(), outputs, Set.of()),
                StandardCharsets.UTF_8);
    }

    private static String planted(Cas cas) throws Exception {
        byte[] payload = "planted".getBytes(StandardCharsets.UTF_8);
        cas.put(payload);
        return Hashing.sha256Hex(payload);
    }

    @Test
    void a_record_whose_output_climbs_out_of_the_target_writes_nothing_there(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        Path module = Files.createDirectories(tmp.resolve("module"));
        Path target = Files.createDirectories(module.resolve("target"));
        plant(actions, "k1", "k1", Map.of("../evil/x.txt", planted(cas)));

        var record = cache.lookup("k1");
        boolean restored = record.isPresent() && cache.restoreFiles(record.get(), target);

        assertThat(module.resolve("evil/x.txt")).as("written outside target/").doesNotExist();
        assertThat(restored).isFalse();
    }

    @Test
    void a_record_whose_output_climbs_out_of_the_target_prunes_nothing_above_it(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        Path module = Files.createDirectories(tmp.resolve("module"));
        Path keep = Files.writeString(
                Files.createDirectories(module.resolve("src")).resolve("Keep.java"), "class Keep {}\n");
        Path target = Files.createDirectories(module.resolve("target"));
        plant(actions, "k1", "k1", Map.of("../evil/x.txt", planted(cas)));

        var record = cache.lookup("k1");
        boolean restored = record.isPresent() && cache.restoreArtifacts(record.get(), target);

        assertThat(keep)
                .as("the module's own source, pruned as an unowned file of target/..")
                .exists();
        assertThat(restored).isFalse();
    }

    @Test
    void a_record_answers_only_for_the_key_it_names(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        byte[] payload = "other".getBytes(StandardCharsets.UTF_8);
        cas.put(payload);
        plant(actions, "wanted", "someone-elses-key", Map.of("app.jar", Hashing.sha256Hex(payload)));

        assertThat(cache.lookup("wanted"))
                .as("a record stored under 'wanted' whose KEY line names another action")
                .isEmpty();
    }

    /** Each unsafe shape of a path or value reads as a miss, and the record is gone afterwards. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/abs/x.txt",
                "C:/x.txt",
                "c:x.txt",
                "../x.txt",
                "a/../../x.txt",
                "a/./x.txt",
                "a//x.txt",
                "a/",
                "a\\x.txt",
                "a\u0000x"
            })
    void an_unsafe_output_path_reads_as_a_miss(String path, @TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        plant(actions, "k1", "k1", Map.of(path, planted(cas)));

        assertThat(cache.lookup("k1")).isEmpty();
        assertThat(actions.resolve("keys").resolve("k1")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "/x", "a\\b", "..", "."})
    void an_unsafe_exec_path_or_output_value_reads_as_a_miss(String bad, @TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        String sha = planted(cas);
        Path keys = Files.createDirectories(actions.resolve("keys"));
        Files.writeString(
                keys.resolve("exec"),
                ActionRecordText.render("t", "exec", Map.of(), Map.of("bin/tool", sha), Set.of(bad)));
        Files.writeString(
                keys.resolve("value"), ActionRecordText.render("t", "value", Map.of(), Map.of("x.txt", bad), Set.of()));

        assertThat(cache.lookup("exec")).as("EXEC %s", bad).isEmpty();
        assertThat(cache.lookup("value")).as("OUTPUT value %s", bad).isEmpty();
    }

    /** A sha256, a marker scalar and a plain nested path are all a record may hold. */
    @Test
    void a_record_of_plain_paths_digests_and_markers_reads_whole(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path actions = tmp.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        String sha = planted(cas);
        plant(actions, "files", "files", Map.of("classes/com/x/A$1.class", sha, "lib/a.jar", sha));
        plant(actions, "marker", "marker", Map.of("total", "3", "|com.x.ATest", "3,0,0", "none", "none discovered"));

        assertThat(cache.lookup("files")).isPresent();
        assertThat(cache.lookup("marker")).isPresent();
    }

    /**
     * A record built in memory never passed the parse; both restores still refuse a path that
     * resolves outside their directory before they write or prune anything.
     */
    @Test
    void both_restores_refuse_a_path_outside_their_directory_for_a_record_built_in_memory(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tmp.resolve("actions"));
        Path module = Files.createDirectories(tmp.resolve("module"));
        Path keep = Files.writeString(
                Files.createDirectories(module.resolve("src")).resolve("Keep.java"), "class Keep {}\n");
        Path target = Files.createDirectories(module.resolve("target"));
        Path stale = Files.writeString(target.resolve("stale.txt"), "stale\n");
        var record = new ActionCache.ActionRecord(
                "t", "k", Map.of(), Map.of("../evil/x.txt", planted(cas)), Set.of(), List.of());

        assertThat(cache.restore(record, target)).isFalse();
        assertThat(cache.restoreArtifacts(record, target)).isFalse();
        assertThat(cache.restoreFiles(record, target)).isFalse();
        assertThat(module.resolve("evil")).doesNotExist();
        assertThat(keep).exists();
        assertThat(stale).as("restore prunes nothing before refusing").exists();
    }
}
