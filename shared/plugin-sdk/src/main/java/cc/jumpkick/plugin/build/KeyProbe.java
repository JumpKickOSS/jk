// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.List;
import java.util.Objects;

/**
 * A step input no file holds — a live database's schema — measured when the step's key is
 * computed. The engine runs {@code java <main> <args>} on the build's JDK with the plugin's own jar
 * and the named step-dependency closures on the classpath, before it looks the step up; the last
 * line the probe prints joins the action key, so a change it sees is a miss like a file edit. A
 * probe that exits non-zero fails the step with its output. It runs on every build that reaches the
 * step and never in a forecast.
 *
 * @param main the class to run, in the plugin's jar or a named closure
 * @param tools step-dependency artifacts whose closures join the classpath
 * @param args the arguments, passed as written
 */
public record KeyProbe(String main, List<String> tools, List<String> args) {

    public KeyProbe {
        Objects.requireNonNull(main, "main");
        tools = List.copyOf(tools);
        args = List.copyOf(args);
    }
}
