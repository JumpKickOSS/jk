// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.runtime.ModuleOutcome;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Every line the build cluster settles on: the module completion tail, the workspace success wedge,
 * the single-project tail and the failure tail. Six verbs render these ({@code build}, {@code test},
 * {@code run}, {@code compile}, {@code image}, {@code native}), which is precisely why they are not
 * on {@code BuildCommand} — a renderer other verbs import is not a facade, it is a shared surface,
 * and the numbers inside it (the zero-padded {@code [k of N]} bracket, the green strikethrough on a
 * finished coordinate, the bright-black {@code took …}) have to agree across all six or the same
 * build reads differently depending on which verb was typed.
 *
 * <p>Nothing here reaches the engine or the filesystem except {@link #builtArtifact}, which stats the
 * artifact the build just wrote to decide whether to name it.
 */
final class BuildTails {

    private BuildTails() {}

    /**
     * A finished unit's live-tail line: {@code ✓ [01 of 16] group:artifact took 16ms}. No leading
     * indent (it is complete, not active); the numerator is zero-padded to the denominator's width;
     * the duration is normalized like every other jk duration ({@link ConsoleSpec#took}). Colors:
     * green check, bright-black brackets around a plain {@code NN of MM} count, the
     * {@code group:artifact} in green with strikethrough (done, web success color), and the
     * bright-black italic {@code took …} suffix. A failed unit keeps the red cross and
     * {@code — failed}.
     */
    static String completionLine(boolean ok, int index, int total, String coord, long millis) {
        var th = Theme.active();
        String mark = Theme.colorize(ok ? Glyphs.CHECK : Glyphs.CROSS, ok ? th.success() : th.error());
        StringBuilder sb = new StringBuilder();
        sb.append(mark)
                .append(' ')
                .append(ConsoleSpec.countBracket(index, total, th))
                .append(' ');
        if (ok) {
            // Green + strike matches web success modules (was plain white strike).
            sb.append(Theme.colorize(coord, th.success().crossedOut()))
                    .append(' ')
                    .append(ConsoleSpec.took(Duration.ofMillis(millis)));
        } else {
            sb.append(JkManager.coloredModule(coord)).append(' ').append(Theme.colorize("— failed", th.error()));
        }
        return sb.toString();
    }

    /** Dim italic {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime}. */
    static String elapsedSince(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return ConsoleSpec.took(Duration.ofMillis(ms));
    }

    /** The green {@code Build successful} lead that opens every build success message. */
    static String buildOk() {
        return Theme.colorize("Build successful", Theme.active().success());
    }

    /**
     * Workspace success wedge
     *
     * <ul>
     * <li>nothing entered → {@code all modules up to date}
     * <li>entered modules, none did productive work → {@code checked N modules, all up to date}
     * <li>some productive work → {@code built K modules} (optionally {@code, checked M})
     * </ul>
     *
     * @param modules outcomes from this run (may be empty on the fully-cached shortcut)
     * @param planned entered-module count from the execute plan (0 when fully cached)
     * @param start wall-clock start, {@link System#nanoTime}
     */
    static String successTail(List<ModuleOutcome> modules, int planned, long start) {
        if (planned == 0) {
            return upToDateTail("all modules", start);
        }
        int built = 0;
        int checked = 0;
        if (modules != null) {
            for (var m : modules) {
                if (!m.success()) continue;
                if (m.didWork()) built++;
                else checked++;
            }
        }
        // Older engines omit didWork (defaults true) — fall back to planned count as "built".
        if (built == 0 && checked == 0) {
            return modulesTail(planned, start);
        }
        if (built == 0) {
            return buildOk()
                    + ", checked "
                    + Theme.colorize(
                            String.valueOf(checked > 0 ? checked : planned),
                            Theme.active().focused())
                    + " module"
                    + ((checked > 0 ? checked : planned) == 1 ? "" : "s")
                    + ", all up to date "
                    + elapsedSince(start);
        }
        if (checked == 0) {
            return modulesTail(built, start);
        }
        return buildOk()
                + ", built "
                + Theme.colorize(String.valueOf(built), Theme.active().focused())
                + " module"
                + (built == 1 ? "" : "s")
                + ", checked "
                + Theme.colorize(String.valueOf(checked), Theme.active().focused())
                + " module"
                + (checked == 1 ? "" : "s")
                + " "
                + elapsedSince(start);
    }

    /** Success tail {@code Build successful for N modules took T} (productive work) — N bold-white. */
    private static String modulesTail(int total, long start) {
        return buildOk()
                + " for "
                + Theme.colorize(String.valueOf(total), Theme.active().focused())
                + " module"
                + (total == 1 ? "" : "s")
                + " "
                + elapsedSince(start);
    }

    /** Success tail {@code Build successful, <scope> up to date took T} — when nothing was rebuilt. */
    private static String upToDateTail(String scope, long start) {
        return buildOk() + ", " + scope + " up to date " + elapsedSince(start);
    }

    /**
     * Single-project success tail from the engine's project summary (thin-client path — the client
     * has no local {@code BuildLayout}): {@code Build successful, project up to date} when nothing
     * was rebuilt, else {@code Build successful. Built <artifact>} naming the headline output. No
     * duration — the framework appends it.
     */
    static String projectTail(String buildOutcome, Path moduleRoot, ProjectInfo info) {
        if ("up-to-date".equals(buildOutcome)) {
            return buildOk() + ", project up to date";
        }
        String art = info == null ? "" : builtArtifact(moduleRoot, info);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /**
     * The headline artifact this build produced, as {@code ". Built <relpath>"} in the path color —
     * the native binary/library if present, else the assembly jar, else the plain jar. Empty when
     * none exists. Shared with {@code jk native}.
     */
    static String builtArtifact(Path moduleRoot, ProjectInfo info) {
        for (String candidate :
                List.of(info.nativeBinPath(), info.nativeLibPath(), info.assemblyJarPath(), info.mainJarPath())) {
            if (candidate.isEmpty()) continue;
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return ". Built "
                        + Theme.colorize(
                                relForDisplay(moduleRoot, p), Theme.active().path());
            }
        }
        return "";
    }

    /** Failure tail {@code group:name took T} — coord colored, {@code took T} bright-black. */
    static String failureTail(String coord, long start) {
        return Coord.module(coord) + " " + elapsedSince(start);
    }

    private static String relForDisplay(Path base, Path p) {
        try {
            return base.relativize(p).toString().replace(File.separatorChar, '/');
        } catch (RuntimeException e) {
            return p.getFileName().toString();
        }
    }
}
