// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The host changes recommended for this machine now, in the order a person reads them: the Defender
 * exclusions first, then long paths, then the per-user settings. A change already in place is left
 * out, so a set-up machine has an empty plan.
 */
public record SetupPlan(List<SetupChange> changes) {

    public SetupPlan {
        changes = List.copyOf(changes);
    }

    /** The plan for {@code check}'s machine, excluding the roots {@code discovery} proposes. */
    public static SetupPlan of(WindowsHostCheck check, List<RootDiscovery.Root> roots) {
        List<SetupChange> out = new ArrayList<>();
        if (check.defenderState() == WindowsHostCheck.Defender.ON) {
            for (RootDiscovery.Root root : roots) {
                String path = root.path().toString();
                if (check.memo().exclusion(path) == SetupMemo.Exclusion.VERIFIED) continue;
                out.add(new SetupChange(
                        SetupChange.excludeId(path),
                        "Exclude " + path + " from Defender",
                        capitalized(root.why()) + "; Defender scans every file a build writes there.",
                        root.note() == null ? "Builds and the cache stop paying for that scan." : root.note() + ".",
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
        return new SetupPlan(out);
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
