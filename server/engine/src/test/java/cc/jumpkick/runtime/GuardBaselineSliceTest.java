// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each guard lane keys on its own slice of the baseline, never on the whole file. */
class GuardBaselineSliceTest {

    private static final String RULES = """
            [guards.no-runtime]
            kind = "forbid"
            signatures = ["java.lang.Runtime"]
            owner = "x.Owner"
            instead = "i"
            why = "w"
            [guards.no-zzz]
            kind = "text"
            pattern = "zzz"
            hit = "zzz"
            instead = "i"
            why = "w"
            """;

    @Test
    void a_module_lane_keeps_its_slice_digest_while_another_module_or_a_root_rule_moves(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        RuleSet rules = GuardRules.load(root, GuardsConfig.ABSENT).rules();
        Path file = GuardsPresence.baselineFile(root);
        Files.createDirectories(file.getParent());

        write(file, site("A.java:1", "a"), site("B.java:1", "b"), site("README.md:1", ""));
        String a = GuardKeys.baselineSlice(root, Lane.MODULE, "a", rules);
        String b = GuardKeys.baselineSlice(root, Lane.MODULE, "b", rules);
        String tree = GuardKeys.baselineSlice(root, Lane.TREE, "", rules);

        write(file, site("A.java:1", "a"), site("B.java:2", "b"), site("README.md:1", ""));
        assertEquals(a, GuardKeys.baselineSlice(root, Lane.MODULE, "a", rules), "b's slice is not a's");
        assertEquals(tree, GuardKeys.baselineSlice(root, Lane.TREE, "", rules), "nor the tree lane's");
        assertNotEquals(b, GuardKeys.baselineSlice(root, Lane.MODULE, "b", rules), "b's own slice moves");

        write(file, site("A.java:1", "a"), site("B.java:2", "b"), site("README.md:9", ""));
        assertEquals(a, GuardKeys.baselineSlice(root, Lane.MODULE, "a", rules), "a root rule is not a's");
        assertNotEquals(tree, GuardKeys.baselineSlice(root, Lane.TREE, "", rules), "the tree lane's slice moves");
    }

    @Test
    void an_undeclared_id_is_read_by_a_module_lane_in_its_own_slice_and_the_roots(@TempDir Path root) throws Exception {
        Path file = GuardsPresence.baselineFile(root);
        Files.createDirectories(file.getParent());
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "");
        RuleSet rules = GuardRules.load(root, GuardsConfig.ABSENT).rules();
        BaselineFile.write(file, new Baseline(Map.of("suite-test", rb(site("X:1", "c")))));
        String c = GuardKeys.baselineSlice(root, Lane.MODULE, "c", rules);
        String d = GuardKeys.baselineSlice(root, Lane.MODULE, "d", rules);

        BaselineFile.write(file, new Baseline(Map.of("suite-test", rb(site("X:1", "c"), site("Y:1", "")))));
        assertNotEquals(c, GuardKeys.baselineSlice(root, Lane.MODULE, "c", rules));
        assertNotEquals(d, GuardKeys.baselineSlice(root, Lane.MODULE, "d", rules));
    }

    private static void write(Path file, Entry moduleA, Entry moduleB, Entry treeEntry) throws Exception {
        BaselineFile.write(file, new Baseline(Map.of("no-runtime", rb(moduleA, moduleB), "no-zzz", rb(treeEntry))));
    }

    private static RuleBaseline rb(Entry... entries) {
        return new RuleBaseline(Map.of(), List.of(entries));
    }

    private static Entry site(String at, String lane) {
        return new Entry.Site(at, "kept", lane);
    }
}
