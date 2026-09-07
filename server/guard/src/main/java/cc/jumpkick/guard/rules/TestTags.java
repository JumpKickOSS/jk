// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The workspace's test-tag vocabulary: every tag the root manifest's {@code [test]} and {@code
 * [profiles.*]} include or exclude. A tier rule naming a tag outside it is a load error — a tag no
 * tier owns is a test no build runs. Members inherit the root's vocabulary; a tag a member alone
 * declares is not one the workspace's tiers know.
 */
public final class TestTags {

    private TestTags() {}

    public static Set<String> vocabulary(Path root) {
        Set<String> tags = new TreeSet<>();
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return tags;
        addTags(tags, manifest);
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            for (Profile p : build.profiles().byName().values()) {
                tags.addAll(p.includeTags());
                tags.addAll(p.excludeTags());
            }
        } catch (IOException | RuntimeException e) {
            // a manifest that does not parse has its own diagnostics; the vocabulary is what [test] says
        }
        return tags;
    }

    private static void addTags(Set<String> tags, Path manifest) {
        try {
            JkBuildParser.TestTomlTags t = JkBuildParser.parseTestTags(manifest);
            tags.addAll(t.includeTags());
            tags.addAll(t.excludeTags());
        } catch (RuntimeException e) {
            // see above
        }
    }
}
