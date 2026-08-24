// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;

/**
 * {@code jk dev} — <strong>alias for {@code jk watch run}</strong>: run the app and rebuild on
 * source changes (Boot DevTools / process restart / device deploy). Prefer documenting {@code jk
 * watch}; keep {@code dev} as the short name for the run loop.
 *
 * <p>Implementation is entirely {@link WatchCommand} (alias registration + shared {@code
 * AppWatchLoop}). This class remains only so help listings can describe the short name without
 * duplicating the watch surface.
 */
public final class DevCommand implements CliCommand {

    private final WatchCommand watch = new WatchCommand();

    @Override
    public String name() {
        return "dev";
    }

    @Override
    public String description() {
        return "Alias for `jk watch run` — app + rebuild on change";
    }

    @Override
    public List<Opt> options() {
        return watch.options();
    }

    @Override
    public List<Param> parameters() {
        // App args only — verb is fixed to run.
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments passed to the application."));
    }

    @Override
    public int run(Invocation in) throws Exception {
        // Rebuild as watch run: prepend the run verb so WatchCommand's parser sees a normal shape.
        Invocation.Builder b = Invocation.builder();
        b.addPositional("run");
        for (String p : in.positionals()) b.addPositional(p);
        // Flags the user passed on `jk dev` are already on Session/GlobalOptions; WatchCommand
        // re-reads GlobalOptions.from(in) — re-emit known option values from the original invocation.
        for (String name : List.of("cache-dir", CommonOpts.JDKS_DIR, "variant", "features")) {
            in.value(name).ifPresent(v -> b.putValue(name, v));
        }
        return watch.run(b.build());
    }
}
