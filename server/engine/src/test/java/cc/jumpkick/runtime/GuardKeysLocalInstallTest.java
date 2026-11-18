// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model lane's key, and the guard salt of every module's preflight fingerprint, name whether
 * this run takes a snapshot a local install served (a developer's build) or refuses it (CI, jk
 * release, jk publish): one lock, two verdicts. Keyed on the manifests and the lock alone, {@code
 * CI=1 jk build} after a developer's green build read "up to date" and never ran the rule.
 */
class GuardKeysLocalInstallTest {

    @TempDir
    Path root;

    @Test
    void a_lock_with_a_local_install_row_keys_the_model_lane_by_who_builds_and_any_other_lock_does_not()
            throws Exception {
        Files.writeString(root.resolve("jk.toml"), "group = \"ex\"\nname = \"app\"\nversion = \"1.0.0\"\n");
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));

        Files.writeString(root.resolve("jk-guards.toml"), "[guards]\n");
        lock(row("zz.lib:liba:jar:", "0.1.0-SNAPSHOT", RepositorySpec.LOCAL_INSTALL));
        assertThat(salt(ReleaseVerb.RELEASE))
                .as("every module's preflight fingerprint: a release misses a developer's dirty memo")
                .isNotEqualTo(salt(ReleaseVerb.NONE));
        assertThat(tokens(build, ReleaseVerb.RELEASE))
                .as("a release asks for the refusing verdict")
                .contains("local-install:jk release")
                .isNotEqualTo(tokens(build, ReleaseVerb.NONE));
        assertThat(tokens(build, ReleaseVerb.PUBLISH)).contains("local-install:jk publish");

        lock(row("org.slf4j:slf4j-api:jar:", "2.0.16", "central+https://repo1.maven.org/maven2/"));
        assertThat(tokens(build, ReleaseVerb.RELEASE))
                .as("a lock with no install row: one verdict for every build")
                .isEqualTo(tokens(build, ReleaseVerb.NONE))
                .noneMatch(t -> t.startsWith("local-install:"));
        assertThat(salt(ReleaseVerb.RELEASE)).isEqualTo(salt(ReleaseVerb.NONE));
    }

    private String salt(ReleaseVerb verb) throws Exception {
        return SessionContext.where(
                SessionContext.current().withReleaseVerb(verb), () -> ModuleFingerprints.guardSalt(root));
    }

    private List<String> tokens(JkBuild build, ReleaseVerb verb) throws Exception {
        return SessionContext.where(
                SessionContext.current().withReleaseVerb(verb), () -> GuardKeys.modelTokens(root, build));
    }

    private void lock(Lockfile.Artifact row) throws Exception {
        LockfileReader.clearCache();
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                null,
                null,
                List.of(row),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
        LockfileWriter.write(lock, root.resolve("jk-lock.toml"));
    }

    private static Lockfile.Artifact row(String name, String version, String source) {
        return new Lockfile.Artifact(
                name, version, source, "sha256:" + "a".repeat(64), null, List.of(Scope.MAIN), List.of(), null);
    }
}
