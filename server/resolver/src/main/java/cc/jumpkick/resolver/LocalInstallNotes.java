// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The lines a version asked for by name earns when it resolves nowhere and this machine's installs
 * were asked too ({@link cc.jumpkick.repo.RepoGroup#withLocalInstalls}): what was asked, that the
 * installs here do not hold it, and the two ways out, so an agent reading the failure on a machine
 * that never ran the other project's {@code jk install} knows what to do next.
 */
final class LocalInstallNotes {

    /** Where the error sends a reader for the whole story. */
    static final String SEE = "see \"Using another project's install\" in dependencies.md";

    private LocalInstallNotes() {}

    /**
     * The line {@code wanted} earns when it resolved nowhere, or null when it earns none: a
     * {@code -SNAPSHOT}, naming the snapshot policy of each repository asked and the installs; a
     * release no repository lists any version of, when the installs were asked.
     */
    static @Nullable String of(RepoGroup group, Coordinate wanted, String display) {
        List<MavenRepo> asked = group.repositoriesFor(wanted);
        List<MavenRepo> installs = asked.stream().filter(group::isLocalInstall).toList();
        String version = wanted.version();
        if (!Versions.isSnapshot(version)) {
            return !installs.isEmpty() && listsNothing(group, wanted) ? release(version, display, installs) : null;
        }
        List<String> serving = new ArrayList<>();
        List<String> refusing = new ArrayList<>();
        for (MavenRepo repo : asked) {
            if (installs.contains(repo)) continue;
            (repo.servesSnapshots() ? serving : refusing).add(repo.name() + " (" + repo.policyLabel() + ")");
        }
        return snapshot(version, display, serving, refusing, installs);
    }

    /** True when no repository {@code coord} is asked of lists a release of it: no catalog knows it. */
    private static boolean listsNothing(RepoGroup group, Coordinate coord) {
        try {
            return group.availableVersions(coord).isEmpty();
        } catch (IOException unreachable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A {@code -SNAPSHOT} no repository holds. {@code serving} and {@code refusing} name the
     * repositories asked other than the installs, each with its policy; {@code installs} are the
     * installs asked, empty when a repository claims the package's group.
     */
    static String snapshot(
            String wanted, String display, List<String> serving, List<String> refusing, List<MavenRepo> installs) {
        String remotes = serving.isEmpty()
                ? "no repository for " + display + " serves snapshots: " + String.join(", ", refusing)
                : "the snapshot repositories for " + display + " do not list it: " + String.join(", ", serving);
        if (installs.isEmpty()) {
            return serving.isEmpty()
                    ? wanted + " is a snapshot and " + remotes + "; add one to [repositories]"
                    : wanted + " is a snapshot; " + remotes;
        }
        return wanted + " is a snapshot, " + notInstalled(installs) + ", and " + remotes + "; " + wayOut();
    }

    /** A release no repository lists any version of, and no install here holds. */
    static String release(String wanted, String display, List<MavenRepo> installs) {
        return wanted + " is in no repository for " + display + ", and " + notInstalled(installs) + "; " + wayOut();
    }

    private static String notInstalled(List<MavenRepo> installs) {
        return "not installed on this machine (" + where(installs) + ")";
    }

    private static String wayOut() {
        return "run jk install in its project on this machine, or add a repository that serves it to"
                + " [repositories]; " + SEE;
    }

    /** The installs' directories, as this machine spells them. */
    private static String where(List<MavenRepo> installs) {
        List<String> dirs = new ArrayList<>(installs.size());
        for (MavenRepo install : installs) {
            String dir;
            try {
                dir = Path.of(install.baseUrl()).toString();
            } catch (IllegalArgumentException notAPath) {
                dir = install.baseUrl().toString();
            }
            dirs.add(dir);
        }
        return String.join(", ", dirs);
    }
}
