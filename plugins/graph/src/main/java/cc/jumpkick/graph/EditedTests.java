// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * What to test after an edit jk made: the modules holding the edited files, by directory, not the
 * whole workspace, whose unrelated failures an agent would otherwise chase. A rename the tests of
 * the modules it edited do not prove done says so.
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

    /**
     * The line after a rename whose every use the compiled classes record was renamed: whether it is
     * complete, then what to test.
     */
    static String renamed(Modules modules, Collection<String> edited, boolean complete) {
        return (complete
                        ? "Every use the compiled classes record is renamed; no search needed. "
                        : "Every use the compiled classes record is renamed. Not complete: the note below names the"
                                + " modules with no compiled classes. ")
                + next(modules, edited, complete);
    }

    /**
     * The line that says so; a single-module project, or an edit at the workspace root, is tested
     * whole. It says the task is done after the test only when {@code complete}: no module or test
     * set lacks the compiled classes that find its calls.
     */
    static String next(Modules modules, Collection<String> edited, boolean complete) {
        String done = complete ? DONE : "";
        Set<String> classes = testClasses(modules, edited);
        Set<String> touched = new TreeSet<>();
        boolean whole = false;
        for (String rel : edited) {
            Modules.Module m = modules.of(rel);
            if (m == null || m.prefix().isEmpty()) {
                whole = true;
                break;
            }
            touched.add(m.prefix().substring(0, m.prefix().length() - 1));
        }
        if (whole || touched.isEmpty()) {
            if (classes.isEmpty()) return "Test next: run(kind=test), or jk test." + done;
            return "Test the test classes it edited: run(kind=test, classes=" + String.join(",", classes)
                    + "), or jk test -S " + String.join(" -S ", classes) + "." + done;
        }
        String only = String.join(",", touched);
        if (classes.isEmpty()) {
            return "Test the modules it edited: run(kind=test, only=" + only + "), or jk test -m " + only + "." + done;
        }
        return "Test the test classes it edited: run(kind=test, only=" + only + ", classes=" + String.join(",", classes)
                + "), or jk test -m " + only + " -S " + String.join(" -S ", classes) + "." + done;
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
