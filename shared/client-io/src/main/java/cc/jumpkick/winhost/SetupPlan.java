// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The host changes recommended for this machine now, in the order a person reads them: the Defender
 * exclusions first, then long paths, then the per-user settings. Each is one row with its own
 * checkbox, so a later kind of change joins the same list. A change already in place is left out,
 * so a set-up machine has an empty plan.
 *
 * @param measuredMs the scan probe's cost per 1,000 files in {@code measuredIn} before any change;
 *     -1 when not measured
 * @param measuredIn the folder the probe ran in, the first proposed exclusion; null when none
 */
public record SetupPlan(
        List<SetupChange> changes,
        long measuredMs,
        @Nullable String measuredIn) {

    public SetupPlan {
        changes = List.copyOf(changes);
    }

    public SetupPlan(List<SetupChange> changes) {
        this(changes, -1, null);
    }

    /**
     * The plan for {@code check}'s machine, excluding the roots discovery proposes. {@code probe}
     * measures the first exclusion's folder, so each exclusion shows what the scan costs here.
     */
    public static SetupPlan of(WindowsHostCheck check, List<RootDiscovery.Root> roots, ScanCost.Probe probe) {
        List<SetupChange> out = new ArrayList<>();
        long measured = -1;
        String measuredIn = null;
        if (check.defenderState() == WindowsHostCheck.Defender.ON) {
            for (RootDiscovery.Root root : roots) {
                String path = root.path().toString();
                if (check.memo().exclusion(path) == SetupMemo.Exclusion.VERIFIED) continue;
                if (measuredIn == null) {
                    measuredIn = path;
                    measured = probe.perThousand(root.path());
                }
                out.add(new SetupChange(
                        SetupChange.excludeId(path),
                        "Exclude " + path + " from Defender",
                        capitalized(root.why()) + "; Defender scans every file a build writes there.",
                        effect(root, path, measuredIn, measured),
                        SetupChange.Kind.EXCLUDE,
                        path));
            }
        }
        if (!check.longPathsEnabled()) {
            out.add(new SetupChange(
                    SetupChange.LONG_PATHS,
                    "Turn on long paths in Windows",
                    "Windows stops paths at 260 characters.",
                    "Deep build outputs and dependency trees stop failing.",
                    SetupChange.Kind.LONG_PATHS,
                    null));
        }
        if (Boolean.FALSE.equals(check.facts().gitLongPaths())) {
            out.add(new SetupChange(
                    SetupChange.GIT_LONG_PATHS,
                    "Turn on long paths in git",
                    "git refuses to check out a path past 260 characters.",
                    "Clones and checkouts with deep paths work.",
                    SetupChange.Kind.GIT_LONG_PATHS,
                    null));
        }
        WindowsHostCheck.Policy desktop = check.desktopPolicy();
        if (!desktop.loadsProfile() && !desktop.byGroupPolicy()) {
            out.add(new SetupChange(
                    SetupChange.EXECUTION_POLICY,
                    "Let PowerShell load the JumpKick profile block",
                    "A new Windows PowerShell is " + desktop.name() + " and skips your profile.",
                    "Sets RemoteSigned for you alone, so jk and its hooks load in every new shell.",
                    SetupChange.Kind.EXECUTION_POLICY,
                    null));
        }
        return new SetupPlan(out, measured, measuredIn);
    }

    private static String effect(RootDiscovery.Root root, String path, String measuredIn, long measured) {
        String note = root.note() == null ? "" : " " + capitalized(root.note()) + ".";
        if (measured <= 0) return "Builds and the cache stop paying for that scan." + note;
        String cost = ScanCost.seconds(measured) + " per 1,000 build files";
        if (path.equals(measuredIn))
            return "Measured here: writing and reading back costs " + cost + " with the scan." + note;
        return "Measured in " + measuredIn + ": " + cost + " with the scan." + note;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public Optional<SetupChange> change(String id) {
        return changes.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** The changes {@code ids} name, in plan order; ids the plan does not hold are ignored. */
    public List<SetupChange> chosen(Set<String> ids) {
        return changes.stream().filter(c -> ids.contains(c.id())).toList();
    }

    /** The exclusion roots among {@code changes}. */
    public static List<String> roots(List<SetupChange> changes) {
        List<String> out = new ArrayList<>();
        for (SetupChange c : changes) {
            String path = c.path();
            if (c.kind() == SetupChange.Kind.EXCLUDE && path != null) out.add(path);
        }
        return out;
    }

    private static String capitalized(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
