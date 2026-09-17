// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The scope flags {@code jk add} and {@code jk remove} share: one of {@code --test}, {@code
 * --runtime}, {@code --provided}, {@code --processor} names a table, {@code --processor --test}
 * together name {@code [test-processor-dependencies]}, and no flag is {@code [dependencies]}.
 */
final class DepScopeFlags {

    private DepScopeFlags() {}

    static List<Opt> options() {
        return List.of(
                Opt.flag("Test scope (with --processor: test-only processors)", "--test"),
                Opt.flag("Runtime scope", "--runtime"),
                Opt.flag("Provided scope", "--provided"),
                Opt.flag("Annotation processor scope", "--processor"));
    }

    /**
     * The scope the flags select, or {@code null} after printing the refusal when they name more
     * than one table.
     */
    static @Nullable Scope resolve(String verb, Invocation in) {
        boolean test = in.isSet("test");
        boolean runtime = in.isSet("runtime");
        boolean provided = in.isSet("provided");
        boolean processor = in.isSet("processor");
        if (test && processor && !runtime && !provided) return Scope.TEST_PROCESSOR;
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            CommandWedge.printFail(
                    verb,
                    "--test / --runtime / --provided / --processor are mutually exclusive"
                            + " (--processor --test together is the test-only processor scope)");
            return null;
        }
        return test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
    }
}
