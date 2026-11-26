// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.release.ReleaseTable;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One publish run's release: the workspace root's {@code [publish] release} bucket, the release's
 * name, version and tag, the platforms {@code [release]} lists, this host's platform, and whether
 * the run stages this host's files or is the final run. Read from the workspace root, whose tables
 * govern the whole release.
 */
public record ReleaseRun(
        Path root,
        JkBuild rootBuild,
        String name,
        String version,
        String tag,
        ReleaseTarget.Bucket target,
        List<String> platforms,
        String host,
        boolean stage,
        boolean dryRun) {

    public ReleaseRun {
        platforms = List.copyOf(platforms);
    }

    /**
     * The release of the workspace around {@code dir}, as {@code args} runs it.
     *
     * @throws IllegalStateException when the root names no bucket to release to
     */
    public static ReleaseRun of(Path dir, TailArgs.Publish args) throws IOException {
        Path root = WorkspaceLocator.findRoot(dir).orElse(dir);
        Path manifest = ManifestPaths.manifestIn(root);
        JkBuild rootBuild = JkBuildParser.parse(manifest);
        String release = rootBuild.pomMetadata().release();
        if (release == null) throw new IllegalStateException("no [publish] release in " + manifest);
        if (!(ReleaseTarget.parse(release) instanceof ReleaseTarget.Bucket bucket)) {
            throw new IllegalStateException("release = \"" + release + "\" is not supported yet; use a bucket");
        }
        ReleaseTable table = JkBuildParser.releaseTable(manifest);
        String version = SnapshotQualifier.version(rootBuild.project().version());
        return new ReleaseRun(
                root,
                rootBuild,
                rootBuild.project().name(),
                version,
                ReleaseTag.render(table.tag(), version),
                bucket,
                table.platformsOrHost(),
                ReleasePlatform.current(),
                args.stage(),
                args.dryRun());
    }

    /** True when this host's platform-specific files belong in the release. */
    public boolean hostListed() {
        return platforms.contains(host);
    }

    /** The listed platforms other than this host's: their files come from their own stage runs. */
    public List<String> others() {
        List<String> out = new ArrayList<>(platforms);
        out.remove(host);
        return List.copyOf(out);
    }

    /** Where the release's files go: {@code {prefix}{version}/}. */
    public String destination() {
        return target.version(version).toString();
    }

    /** The line a final run refuses with when {@code platform}'s files are not staged. */
    public String lacks(String platform) {
        return "release " + tag + " lacks " + platform + "; run jk publish --stage on that platform first";
    }
}
