// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.task.KeyContract;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every {@link KeyContract} records the digest of the code it names. Action keys carry no engine
 * identity but these digests, so engine code that produces or judges a result and changed without
 * its digest would replay results the old code made.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class KeyContractRules {

    private static final String FILE = "server/engine/src/main/java/cc/jumpkick/task/KeyContract.java";

    @Guard(
            id = "key-contract-recorded",
            why =
                    "action keys name the engine's own code only by the digests KeyContract records, so a change to a contract's code with the old digest still recorded replays the results the old code produced",
            instead = "record the digest this guard prints for the contract in KeyContract.java")
    void keyContractRecorded(Text text, Violations v) {
        int files = 0;
        for (KeyContract contract : KeyContract.values()) {
            SortedMap<String, String> code = new TreeMap<>();
            for (String glob : contract.sources()) {
                for (String rel : text.files(glob)) {
                    if (contract.keeps(rel)) code.put(rel, text.blanked(rel, Blank.COMMENTS));
                }
            }
            if (code.isEmpty())
                throw new IllegalStateException("contract " + contract + " reads no file; its sources moved");
            files += code.size();
            String digest = KeyContract.digest(code);
            if (!digest.equals(contract.recorded())) {
                v.add(
                        new TextSite(FILE, line(text, contract), contract.name()),
                        "the code of " + contract + " changed: record \"" + digest + "\" for it (recorded \""
                                + contract.recorded() + "\")");
            }
        }
        if (files < 200)
            throw new IllegalStateException(
                    "files the key contracts read: " + files + " — 300 when measured; the walk moved");
        v.population(files);
    }

    /** The line of {@code contract}'s constant in {@link #FILE}, or 0 when the file no longer spells it. */
    private static int line(Text text, KeyContract contract) {
        int n = 0;
        for (String l : text.lines(FILE)) {
            n++;
            if (l.strip().startsWith(contract.name() + "(")) return n;
        }
        return 0;
    }
}
