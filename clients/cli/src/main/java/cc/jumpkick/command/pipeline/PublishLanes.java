// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeKind;
import cc.jumpkick.forge.GitHubRepository;
import cc.jumpkick.forge.ResolvedToken;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.wire.runtime.PublishLane;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which lanes a {@code jk publish} runs: every lane the project gives a target, in {@link
 * PublishLane} order, narrowed by {@code --only}; {@code --stage} runs the release lane alone. A
 * lane {@code --only} names without a target is refused naming the key that gives it one.
 */
record PublishLanes(List<PublishLane> lanes, @Nullable String refusal) {

    /**
     * The lanes of one run. {@code only} is every {@code --only} value, each a lane or a comma list
     * of lanes; {@code library}, {@code release} and {@code image} say which lanes have a target. A
     * dry run of a project with no target assembles the library lane, so a preview needs none.
     */
    static PublishLanes select(
            List<String> only, boolean stage, boolean dryRun, boolean library, String release, boolean image) {
        Set<PublishLane> named = EnumSet.noneOf(PublishLane.class);
        for (String value : only) {
            for (String word : value.split(",")) {
                String w = word.strip();
                if (w.isEmpty()) continue;
                PublishLane lane = PublishLane.parse(w).orElse(null);
                if (lane == null) return refused("--only takes library, release or image, not `" + w + "`");
                named.add(lane);
            }
        }
        if (named.contains(PublishLane.IMAGE)) {
            return refused("--only image: jk publish does not push images yet; use jk image");
        }
        String releaseRefusal = release.isEmpty() ? null : forgeRefusal(release);
        if (stage) {
            if (named.contains(PublishLane.LIBRARY)) {
                return refused("--stage uploads release files only; drop --only library");
            }
            if (release.isEmpty()) return refused("--stage needs " + PublishLane.RELEASE.key() + " in jk.toml");
            if (releaseRefusal != null) return refused(releaseRefusal);
            return new PublishLanes(List.of(PublishLane.RELEASE), null);
        }
        boolean[] configured = {library, !release.isEmpty(), image};
        List<PublishLane> lanes = new ArrayList<>();
        for (PublishLane lane : PublishLane.values()) {
            boolean has = configured[lane.ordinal()];
            if (!named.isEmpty() && !named.contains(lane)) continue;
            if (!has) {
                if (named.contains(lane))
                    return refused("--only " + lane.word() + " needs " + lane.key() + " in jk.toml");
                continue;
            }
            // Until jk publish pushes images, a registry gives jk image a target and this run nothing.
            if (lane == PublishLane.IMAGE) continue;
            if (lane == PublishLane.RELEASE && releaseRefusal != null) return refused(releaseRefusal);
            lanes.add(lane);
        }
        if (lanes.isEmpty() && dryRun && named.isEmpty()) return new PublishLanes(List.of(PublishLane.LIBRARY), null);
        if (lanes.isEmpty()) {
            return refused("nothing to publish; set [publish] repository or [publish] release in jk.toml");
        }
        return new PublishLanes(List.copyOf(lanes), null);
    }

    /** Why the release lane cannot ship to {@code release} from this version of jk, or {@code null}. */
    static @Nullable String forgeRefusal(String release) {
        String refused = ReleaseTarget.refusal(release);
        if (refused != null) return "[publish] release: " + refused;
        if (ReleaseTarget.FORGES.contains(release) && !release.equals(ReleaseTarget.GITHUB)) {
            return "release = \"" + release + "\" is not supported yet; use github or a bucket";
        }
        return null;
    }

    /**
     * The token a {@code release = "github"} run writes with: the forge token chain's for the host
     * of {@code dir}'s origin.
     *
     * @throws IllegalArgumentException with one line when origin is not on GitHub or no token is set
     */
    static String githubToken(Path dir, ForgeAuth auth) {
        GitHubRepository repo = GitHubRepository.fromOrigin(dir);
        return auth.resolveSilently(ForgeKind.GITHUB, repo.host())
                .map(ResolvedToken::value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no GitHub token for " + repo.host() + "; run jk auth login github or set GH_TOKEN"));
    }

    private static PublishLanes refused(String why) {
        return new PublishLanes(List.of(), why);
    }
}
