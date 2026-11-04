// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * What to test after an edit jk made: the modules holding the edited files, by directory, not the
 * whole workspace, whose unrelated failures an agent would otherwise chase.
 */
final class EditedTests {

    /** Test classes named one by one at most. */
    static final int MAX_CLASSES = 12;

    private EditedTests() {}

    /**
     * Agents otherwise follow a passing scoped test with a whole-workspace build or test, which only
     * re-proves a change that alters no behaviour.
     */
    static final String DONE = " A rename changes no behaviour: once that test and run(kind=guard) pass, the task"
            + " is done; a full build or test adds nothing.";

    /** The line that says so; a single-module project, or an edit at the workspace root, is tested whole. */
    static String next(Modules modules, Collection<String> edited) {
        Set<String> touched = new TreeSet<>();
        for (String rel : edited) {
            Modules.Module m = modules.of(rel);
            if (m == null || m.prefix().isEmpty()) return "Test next: run(kind=test), or jk test." + DONE;
            touched.add(m.prefix().substring(0, m.prefix().length() - 1));
        }
        if (touched.isEmpty()) return "Test next: run(kind=test), or jk test." + DONE;
        String only = String.join(",", touched);
        Set<String> classes = testClasses(modules, edited);
        if (classes.isEmpty()) {
            return "Test the modules it edited: run(kind=test, only=" + only + "), or jk test -m " + only + "." + DONE;
        }
        String names = String.join(",", classes);
        return "Test the test classes it edited: run(kind=test, only=" + only + ", classes=" + names
                + "), or jk test -m " + only + " -S " + String.join(" -S ", classes) + "." + DONE;
    }

    /**
     * The simple names of the edited test sources: the tests that call the renamed method, which a
     * rename's check needs to run (every test source still compiles). Empty past {@link #MAX_CLASSES},
     * where the modules' whole suites are the shorter instruction.
     */
    static Set<String> testClasses(Modules modules, Collection<String> edited) {
        Set<String> out = new TreeSet<>();
        for (String rel : edited) {
            if (modules.sourceSet(rel) != Modules.SourceSet.TEST || !rel.matches(".*\\.(java|kt)$")) continue;
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            out.add(file.substring(0, file.lastIndexOf('.')));
        }
        return out.size() > MAX_CLASSES ? Set.of() : out;
    }
}
