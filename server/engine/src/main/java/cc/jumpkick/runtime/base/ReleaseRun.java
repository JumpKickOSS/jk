// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.forge.ForgeRepository;
import cc.jumpkick.forge.ForgejoRepository;
import cc.jumpkick.forge.GitHubRepository;
import cc.jumpkick.forge.GitLabProject;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.release.ReleaseIndex;
import cc.jumpkick.release.ReleaseTable;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One publish run's release: the workspace root's {@code [publish] release} target (for a forge,
 * the repository the git remote {@code origin} names), the release's name, version and
 * tag, the platforms {@code [release]} lists, this host's platform, and whether the run stages this
 * host's files or is the final run. Read from the workspace root, whose tables govern the whole
 * release.
 */
public record ReleaseRun(
        Path root,
        JkBuild rootBuild,
        String name,
        String version,
        String tag,
        ReleaseTarget target,
        @Nullable ForgeRepository forge,
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
     * @throws IllegalStateException when the root names no target this jk releases to, or a forge
     *     with an origin that is not on that forge
     */
    public static ReleaseRun of(Path dir, TailArgs.Publish args) throws IOException {
        Path root = WorkspaceLocator.findRoot(dir).orElse(dir);
        Path manifest = ManifestPaths.manifestIn(root);
        JkBuild rootBuild = JkBuildParser.parse(manifest);
        String release = rootBuild.pomMetadata().release();
        if (release == null) throw new IllegalStateException("no [publish] release in " + manifest);
        ReleaseTarget target = ReleaseTarget.parse(release);
        ForgeRepository forge = null;
        if (target instanceof ReleaseTarget.Forge named) {
            try {
                forge = ForgeRepository.fromOrigin(named.kind(), root);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        ReleaseTable table = JkBuildParser.releaseTable(manifest);
        String version = SnapshotQualifier.version(rootBuild.project().version());
        return new ReleaseRun(
                root,
                rootBuild,
                rootBuild.project().name(),
                version,
                ReleaseTag.render(table.tag(), version),
                target,
                forge,
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

    /**
     * Where the release's files go: {@code {prefix}{version}/} in a bucket, the tag's download
     * directory on GitHub and Forgejo, the version's generic package on GitLab.
     */
    public String destination() {
        if (target instanceof ReleaseTarget.Bucket bucket)
            return bucket.version(version).toString();
        return switch (Objects.requireNonNull(forge, "forge")) {
            case GitHubRepository github -> github.download(tag);
            case ForgejoRepository forgejo -> forgejo.download(tag);
            case GitLabProject gitlab -> gitlab.packages(gitlab.api(), name, version);
        };
    }

    /**
     * What a dry run's release would do last: point {@code latest.json} at it, publish its draft, or
     * create the GitLab release; a stage run, upload to the draft or the generic package ({@code
     * null} for a bucket, which has neither).
     */
    public @Nullable String plannedFinish() {
        boolean gitlab = forge instanceof GitLabProject;
        if (stage) {
            if (forge == null) return null;
            return gitlab
                    ? "would upload to the generic package " + name + " " + version
                    : "would upload to the draft release " + tag;
        }
        if (forge == null) return "would point " + ReleaseIndex.LATEST + " at " + version;
        return gitlab ? "would create the release " + tag : "would publish the draft release " + tag;
    }

    /** The line a run refuses with when {@code name} is not in a release that is already out. */
    public String publishedWithout(String name) {
        return publishedWithout(tag, name);
    }

    static String publishedWithout(String tag, String name) {
        return tag + " is already published without " + name + "; raise the version";
    }

    /** The line a final run refuses with when {@code platform}'s files are not staged. */
    public String lacks(String platform) {
        return "release " + tag + " lacks " + platform + "; run jk publish --stage on that platform first";
    }
}
