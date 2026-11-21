// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.OwnerMissing;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The list of the repository's {@code jk-lock.toml} files that {@code LockRowParserTest} reads through
 * both lock grammars is the tree's list: every lock the tree has is on it, and every path on it is a
 * lock in the tree.
 *
 * <p>A declared list and this guard, not a walk of the checkout from the test: the test walked about
 * 100,000 entries from a test JVM while the build that ran it wrote the same volume, so its time was
 * the filesystem's, and it timed out at the fast tier's 20 s in gate 94's merge gate. The
 * walk here is the guard's text view, done once for every text guard of the run.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class LockListRules {

    static final String LIST = "shared/core/src/test/resources/cc/jumpkick/lock/repository-locks.txt";

    /** This guard's own fixture trees, whose locks are no lock of the repository's. */
    private static final String OWN_FIXTURES = "server/guard/fixtures/lock-list-complete/";

    @Guard(
            id = "lock-list-complete",
            why = "LockRowParserTest proves the row grammar reads every lock the repository ships as tomlj does,"
                    + " but only the locks its list names: a lock missing from the list is one nothing reads"
                    + " through both grammars, and a listed path that is gone fails the test for no defect",
            instead = "add the lock's workspace-relative path to " + LIST + " in the same change, or drop the"
                    + " line of the lock that went")
    @Fixture("server/guard/fixtures/lock-list-complete")
    void lockListComplete(Text text, Violations v) {
        List<String> lists = text.files(LIST).stream()
                .filter(rel -> !rel.startsWith(OWN_FIXTURES))
                .toList();
        if (lists.isEmpty()) throw new OwnerMissing("no " + LIST + ": LockRowParserTest reads its locks from it");
        String list = lists.getFirst();
        Set<String> locks = new TreeSet<>();
        for (String rel : text.files("**/jk-lock.toml")) if (!rel.startsWith(OWN_FIXTURES)) locks.add(rel);
        Set<String> listed = new TreeSet<>();
        List<String> lines = text.lines(list);
        for (int i = 0; i < lines.size(); i++) {
            String rel = lines.get(i).strip();
            if (rel.isEmpty() || rel.startsWith("#")) continue;
            listed.add(rel);
            if (!locks.contains(rel))
                v.add(new TextSite(list, i + 1, rel), "lists " + rel + ", which is no jk-lock.toml in the tree");
        }
        if (listed.isEmpty()) throw new OwnerMissing(list + " lists no lock: LockRowParserTest would read none");
        for (String lock : locks) {
            if (!listed.contains(lock))
                v.add(new TextSite(lock, 1, lock), lock + " is not in " + list + ", so no test reads it");
        }
        v.population(locks.size());
    }
}
