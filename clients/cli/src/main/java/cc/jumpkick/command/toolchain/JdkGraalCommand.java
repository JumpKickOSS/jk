// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.jdk.DefaultGraalPolicy;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk jdk graal [<spec>]} — set the default GraalVM-based JDK (Oracle GraalVM or GraalVM CE).
 * It's tracked separately from the default <em>java</em> JDK and backs {@code GRAALVM_HOME} (via
 * the {@code jk activate} hook) and {@code jk native}. With no spec, the newest installed GraalVM
 * is chosen, preferring Oracle GraalVM over GraalVM CE.
 */
public final class JdkGraalCommand implements CliCommand {

    @Override
    public String name() {
        return "graal";
    }

    @Override
    public String description() {
        return "Set a specific GraalVM JDK to be the native-image default";
    }

    @Override
    public List<Opt> options() {
        return List.of(CommonOpts.jdksDir());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of GraalVM you'd like to make the default\n"
                        + "  (ex: 25, lts, latest, graal-25, graalce-25)"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        Path jdksDir = CommonOpts.jdksDirValue(in);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        JdkInventory defaults = JdkInventory.of(registry.jdksRoot());

        List<JdkHit> graals =
                registry.listHits().stream().filter(DefaultGraalPolicy::isGraal).toList();
        if (graals.isEmpty()) {
            CommandWedge.printFail(
                    "JDK",
                    "no GraalVM JDK installed — install one with "
                            + "`jk jdk install native` (or `jk jdk install graalvm-25`).");
            return 1;
        }

        JdkHit chosen;
        if (spec == null || spec.isBlank()) {
            chosen = DefaultGraalPolicy.choose(graals).orElseThrow();
        } else {
            Optional<JdkHit> match = JdkKeywords.isKeyword(spec)
                    ? JdkKeywords.bestInstalledMatch(spec, graals)
                    : registry.findHitBySpec(spec).filter(DefaultGraalPolicy::isGraal);
            if (match.isEmpty()) {
                CommandWedge.printFail("JDK", "no installed GraalVM matches `" + spec + "` (try `jk jdk list`).");
                return 1;
            }
            chosen = match.get();
        }

        String identifier = JdkRegistry.identifierFor(chosen.home());
        defaults.setGraal(new InstalledJdk(identifier, chosen.home()));
        CliOutput.out(Theme.colorize("➜", Theme.active().brightGreen())
                + " The "
                + Theme.colorize("native", Theme.active().focused())
                + " (default GraalVM) JDK is now set to "
                + Theme.colorize(display(chosen), Theme.active().focused())
                + " "
                + Theme.colorize("(" + identifier + ")", Theme.active().darkGray()));
        return 0;
    }

    private static String display(JdkHit hit) {
        Integer major = JdkKeywords.leadingMajor(hit.version());
        String name = hit.vendor().displayName();
        return major != null ? name + " " + major : name + " " + hit.version();
    }
}
