// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.host.Interned;
import cc.jumpkick.version.Versions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settled idle trim empties the process-wide memos and its log fragment names what went. */
class MemoTrimTest {

    @Test
    void the_trim_names_what_it_dropped_and_leaves_the_memos_empty(@TempDir Path dir) throws Exception {
        MemoTrim.drop(null);
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "name = \"m\"\nversion = \"1.0\"\n");
        Files.setLastModifiedTime(toml, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        JkBuildParser.parseLocal(toml);
        TomlScan.scan(toml, "name");
        Versions.compare("1.0.0", "2.0.0");
        Interned.of("org.example:memo-trim-probe");

        String line = MemoTrim.drop(null);
        // The parse also scans the catalog beside the manifest, so the TOML count is at least one.
        assertThat(line)
                .matches("memos dropped: manifests 2, toml files [1-9]\\d*, versions 2, strings [1-9]\\d*,"
                        + " file hashes \\d+, abi tokens \\d+");

        assertThat(MemoTrim.drop(null))
                .as("a second trim on an untouched engine finds nothing")
                .isEqualTo(
                        "memos dropped: manifests 0, toml files 0, versions 0, strings 0, file hashes 0, abi tokens 0");
    }

    /**
     * The trim keeps the manifests and TOML files of the workspace built last, so a developer coming
     * back to a large reactor after a pause finds its manifests still parsed; every other
     * workspace's entries go, and nothing at all is kept when no build has run.
     */
    @Test
    void the_trim_keeps_the_last_built_workspaces_manifests_and_drops_every_other(@TempDir Path dir) throws Exception {
        MemoTrim.drop(null);
        Path kept = manifest(dir.resolve("kept"), "kept");
        Path gone = manifest(dir.resolve("gone"), "gone");
        JkBuildParser.parseLocal(kept);
        JkBuildParser.parseLocal(gone);
        TomlScan.scan(kept, "name");
        TomlScan.scan(gone, "name");

        String line = MemoTrim.drop(kept.getParent());
        assertThat(line)
                .matches("memos dropped: manifests 2, toml files [1-9]\\d*,.*")
                .endsWith("; kept for " + kept.getParent());

        assertThat(MemoTrim.drop(null))
                .as("with no root to keep, the kept entries go too")
                .matches("memos dropped: manifests 2, toml files [1-9]\\d*,.*")
                .doesNotContain("kept for");
    }

    private static Path manifest(Path module, String name) throws Exception {
        Files.createDirectories(module);
        Path toml = module.resolve("jk.toml");
        Files.writeString(toml, "name = \"" + name + "\"\nversion = \"1.0\"\n");
        Files.setLastModifiedTime(toml, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return toml;
    }
}
