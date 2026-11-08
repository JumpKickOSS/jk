// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;

/**
 * {@code jk javadoc}: write the javadoc jar of every library in scope, as {@code jk publish} does,
 * without running tests. {@code jk build} writes javadoc only for a module that says {@code javadoc
 * = true} (or {@code "strict"}); this is the on-demand spelling for everything else.
 *
 * <p>The request asks for docs through the session for this invocation only, restored afterwards
 * so nothing later in the same JVM inherits it.
 */
public final class JavadocCommand implements CliCommand {

    @Override
    public String name() {
        return "javadoc";
    }

    @Override
    public String description() {
        return "Build the javadoc jar of every library (no tests)";
    }

    @Override
    public List<Opt> options() {
        return BuildVerbCommand.build().options();
    }

    @Override
    public int run(Invocation in) throws Exception {
        var previous = SessionContext.current();
        SessionContext.install(previous.withJavadoc(true));
        try {
            return BuildVerbCommand.run(PackageVerb.javadoc(), in, true);
        } finally {
            SessionContext.install(previous);
        }
    }
}
