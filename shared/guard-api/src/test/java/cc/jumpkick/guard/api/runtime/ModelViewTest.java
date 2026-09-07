// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.api.DepScope;
import cc.jumpkick.guard.api.Dependency;
import cc.jumpkick.guard.api.Lock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelViewTest {

    private static final String JSON = """
            {"modules":["","lib","app"],
             "deps":{"lib":{"dependencies":[{"coordinate":"org.x:y","version":"^1","workspace":false},
                                            {"coordinate":"cc.jumpkick:jk-host","version":"0.13.0","workspace":true}],
                            "test-dependencies":[{"coordinate":"org.junit.jupiter:junit-jupiter","version":"6.1.3","workspace":false}],
                            "PROCESSOR":[{"coordinate":"org.p:proc","version":"1","workspace":false}],
                            "not-a-scope":[{"coordinate":"z:z","version":"1","workspace":false}]},
                     "app":{}},
             "lock":[{"coordinate":"org.x:y","version":"1.2","repository":"central","scopes":["main","test"]},
                     {"coordinate":"org.p:proc","version":"1.0","repository":"corp","scopes":["processor"]}],
             "tiers":{"tiers":[{"name":"jk test","include":[],"exclude":["slow","network"]},
                               {"name":"jk test --profile slow","include":["slow"],"exclude":[]},
                               {"name":"jk test --profile network","include":["network"],"exclude":["slow"]}]},
             "toolchain":{"java":{"":25,"lib":17},"kotlin":"2.4.10","repositories":["central","corp"]}}
            """;

    @Test
    void modules_are_listed_in_order() {
        assertThat(ModelView.parse(JSON).modules()).containsExactly("", "lib", "app");
    }

    @Test
    void dependencies_are_grouped_by_scope_table_or_enum_name() {
        ModelView m = ModelView.parse(JSON);
        List<Dependency> main = m.deps("lib", DepScope.MAIN);
        assertThat(main)
                .containsExactly(
                        new Dependency("org.x:y", "^1", DepScope.MAIN, false),
                        new Dependency("cc.jumpkick:jk-host", "0.13.0", DepScope.MAIN, true));
        assertThat(m.deps("lib", DepScope.TEST))
                .extracting(Dependency::coordinate)
                .containsExactly("org.junit.jupiter:junit-jupiter");
        assertThat(m.deps("lib", DepScope.PROCESSOR))
                .as("the enum name is accepted too")
                .hasSize(1);
        assertThat(m.deps("lib", DepScope.RUNTIME)).isEmpty();
        assertThat(m.deps("app", DepScope.MAIN)).isEmpty();
        assertThat(m.deps("missing", DepScope.MAIN)).isEmpty();
    }

    @Test
    void the_lock_lists_artifacts_keyed_by_coordinate() {
        Lock lock = ModelView.parse(JSON).lock();
        assertThat(lock.artifacts()).hasSize(2);
        Lock.Artifact y = lock.artifacts().get(0);
        assertThat(y.coordinate()).isEqualTo("org.x:y");
        assertThat(y.version()).isEqualTo("1.2");
        assertThat(y.repository()).isEqualTo("central");
        assertThat(y.scopes()).containsExactly("main", "test");
        assertThat(y.key()).isEqualTo("lock org.x:y");
        assertThat(y.fingerprint()).isEqualTo("lock org.x:y");
        assertThat(y.file()).isNull();
        assertThat(y.line()).isZero();
    }

    @Test
    void tiers_run_a_test_by_its_tags_with_exclusion_winning() {
        var tiers = ModelView.parse(JSON).tiers();
        assertThat(tiers.tagVocabulary()).containsExactly("network", "slow");
        assertThat(tiers.running(Set.of())).containsExactly("jk test");
        assertThat(tiers.running(Set.of("slow"))).containsExactly("jk test --profile slow");
        assertThat(tiers.running(Set.of("network"))).containsExactly("jk test --profile network");
        assertThat(tiers.running(Set.of("slow", "network")))
                .as("network excludes slow; slow's tier includes it")
                .containsExactly("jk test --profile slow");
        assertThat(tiers.running(Set.of("unknown")))
                .as("an unknown tag runs in the default tier")
                .containsExactly("jk test");
        assertThat(tiers.key()).isEqualTo("tiers");
    }

    @Test
    void the_toolchain_carries_releases_kotlin_and_repositories() {
        var tc = ModelView.parse(JSON).toolchain();
        assertThat(tc.javaRelease()).containsOnly(Map.entry("", 25), Map.entry("lib", 17));
        assertThat(tc.kotlin()).isEqualTo("2.4.10");
        assertThat(tc.repositories()).containsExactly("central", "corp");
        assertThat(tc.fingerprint()).isEqualTo("toolchain");
    }

    @Test
    void a_snapshot_without_optional_tables_parses_to_empties() {
        ModelView m = ModelView.parse("{\"modules\":[\"\"]}");
        assertThat(m.modules()).containsExactly("");
        assertThat(m.deps("", DepScope.MAIN)).isEmpty();
        assertThat(m.lock().artifacts()).isEmpty();
        assertThat(m.tiers().running(Set.of())).isEmpty();
        assertThat(m.tiers().tagVocabulary()).isEmpty();
        assertThat(m.toolchain().javaRelease()).isEmpty();
        assertThat(m.toolchain().kotlin()).isNull();
        assertThat(m.toolchain().repositories()).isEmpty();
    }

    @Test
    void the_empty_model_matches_an_empty_snapshot() {
        ModelView empty = ModelView.empty();
        assertThat(empty.modules()).isEmpty();
        assertThat(empty.lock().artifacts()).isEmpty();
        assertThat(empty.tiers().running(Set.of("x"))).isEmpty();
        assertThat(empty.toolchain().kotlin()).isNull();
    }

    @Test
    void the_snapshot_is_read_from_a_file(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("model.json");
        Files.writeString(json, JSON);
        assertThat(ModelView.read(json).modules()).containsExactly("", "lib", "app");
    }

    @Test
    void scope_tables_spell_the_manifest_tables() {
        assertThat(DepScope.MAIN.table()).isEqualTo("dependencies");
        assertThat(DepScope.TEST.table()).isEqualTo("test-dependencies");
        assertThat(DepScope.PLATFORM.table()).isEqualTo("platform-dependencies");
        assertThat(DepScope.DEV.table()).isEqualTo("dev-dependencies");
    }
}
