// SPDX-License-Identifier: Apache-2.0
package demo;

import java.util.List;

/**
 * Greets its arguments. Named only by {@code demo/commands.properties}, which matches no index
 * convention, so R8 keeps it solely because the {@code [minified] keep} rule in {@code jk.toml}
 * names it.
 */
public final class GreetCommand implements Command {

    @Override
    public String verb() {
        return "greet";
    }

    @Override
    public String run(List<String> args) {
        return "hello, " + (args.isEmpty() ? "world" : String.join(" ", args));
    }
}
