// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code jk jdk default [<spec> | --lts]} — set an installed JDK as the system default. */
public final class JdkDefaultCommand implements CliCommand {

    @Override
    public String name() {
        return "default";
    }

    @Override
    public String description() {
        return "Set a specific Java Development Kit to be the default";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Pick the latest installed LTS JDK (Temurin preferred).", "--lts"), CommonOpts.jdksDir());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to make the default\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        boolean lts = in.isSet("lts");
        Path jdksDir = CommonOpts.jdksDirValue(in);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        JdkInventory defaults = JdkInventory.of(registry.jdksRoot());

        if (lts) {
            if (spec != null && !spec.isBlank()) {
                CommandWedge.printFail("JDK", "--lts and <spec> are mutually exclusive.");
                return Exit.USAGE;
            }
            return applyLts(registry, defaults, CliOutput.stdout(), CliOutput.stderr()) ? 0 : 1;
        }
        if (spec == null || spec.isBlank()) {
            CommandWedge.printFail("JDK", "<spec> required (or pass --lts).");
            return Exit.USAGE;
        }
        Optional<JdkHit> match = JdkKeywords.isKeyword(spec)
                ? JdkKeywords.bestInstalledMatch(spec, registry.listHits())
                : registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            CommandWedge.printFail(
                    "JDK",
                    "no installed JDK matches `" + spec + "` (try `jk jdk list` or `jk jdk install " + spec + "`)");
            return 1;
        }
        applyDefault(match.get(), defaults, CliOutput.stdout());
        return 0;
    }

    static boolean applyLts(JdkRegistry registry, JdkInventory defaults, PrintStream out, PrintStream err)
            throws IOException {
        List<JdkHit> hits = registry.listHits();
        List<JdkHit> ltsHits = new ArrayList<>();
        for (JdkHit hit : hits) {
            Integer m = JdkKeywords.leadingMajor(hit.version());
            if (m != null && JdkLts.isLtsMajor(m)) ltsHits.add(hit);
        }
        if (ltsHits.isEmpty()) {
            err.println("jk jdk default --lts: no LTS JDK installed (try `jk jdk install --lts`).");
            return false;
        }
        ltsHits.sort(Comparator.comparingInt((JdkHit h) ->
                        JdkKeywords.leadingMajor(h.version()) == null ? 0 : JdkKeywords.leadingMajor(h.version()))
                .reversed()
                .thenComparing(h -> h.vendor() == JdkVendor.TEMURIN ? 0 : 1)
                .thenComparing((JdkHit h) -> h.version() == null ? "" : h.version(), Comparator.reverseOrder()));
        applyDefault(ltsHits.getFirst(), defaults, out);
        return true;
    }

    private static void applyDefault(JdkHit hit, JdkInventory defaults, PrintStream out) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        defaults.setDefault(new InstalledJdk(identifier, hit.home()));
        String name = Theme.colorize(renderDisplayName(hit), Theme.active().focused());
        String message = "Default JDK set to " + name + ": " + JdkRender.coord(hit.source(), identifier);
        CommandWedge.envelopeStart(out);
        out.println(JkWedge.chipLine(Glyphs.CHECK, "JDK", GlobalConfig.nerdFont(), message));
    }

    private static String renderDisplayName(JdkHit hit) {
        Integer major = JdkKeywords.leadingMajor(hit.version());
        if (hit.vendor() == JdkVendor.UNKNOWN) return major != null ? "JDK " + major : "JDK " + hit.version();
        String name = hit.vendor().displayName();
        return major != null ? name + " " + major : name + " " + hit.version();
    }
}
