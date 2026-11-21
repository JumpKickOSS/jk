// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.engine.RunSuspensions;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.DurationText;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every line the build cluster settles on: the module completion tail, the workspace success wedge,
 * the single-project tail and the failure tail, and the one settle-line shape ({@link #settle},
 * {@link #UP_TO_DATE}) the install and test tails use too. Six verbs render these ({@code build}, {@code test},
 * {@code run}, {@code compile}, {@code image}, {@code native}), so they live on no one verb — a
 * renderer other verbs import is not a facade, it is a shared surface,
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
     * the duration is normalized like every other jk duration ({@link DurationText#took}). Colors:
     * green check, bright-black brackets around a plain {@code NN of MM} count, the
     * {@code group:artifact} in green with strikethrough (done, web success color), and the
     * bright-black italic {@code took …} suffix. A failed unit keeps the red cross and
     * {@code — failed}; a unit another module's failure stopped reads {@code — stopped}, muted.
     */
    static String completionLine(ModuleOutcome o, int index, int total) {
        return completionLine(o.success(), o.stopped(), index, total, o.coord(), o.millis());
    }

    static String completionLine(boolean ok, int index, int total, String coord, long millis) {
        return completionLine(ok, false, index, total, coord, millis);
    }

    private static String completionLine(boolean ok, boolean stopped, int index, int total, String coord, long millis) {
        var th = Theme.active();
        String mark = stopped
                ? Theme.colorize(Glyphs.CANCELLED, th.midGray())
                : Theme.colorize(ok ? Glyphs.CHECK : Glyphs.CROSS, ok ? th.success() : th.error());
        StringBuilder sb = new StringBuilder();
        sb.append(mark)
                .append(' ')
                .append(ConsoleSpec.countBracket(index, total, th))
                .append(' ');
        if (ok) {
            // Green + strike matches web success modules (was plain white strike).
            sb.append(Theme.colorize(coord, th.success().crossedOut()))
                    .append(' ')
                    .append(DurationText.took(Duration.ofMillis(millis)));
        } else if (stopped) {
            sb.append(JkManager.coloredModule(coord)).append(' ').append(Theme.colorize("— stopped", th.midGray()));
        } else {
            sb.append(JkManager.coloredModule(coord)).append(' ').append(Theme.colorize("— failed", th.error()));
        }
        return sb.toString();
    }

    /** A roll-call line for a module another module's failure stopped: not a failure of its own. */
    static String stoppedLine(String coord) {
        return Theme.colorize("stopped by the failure:", Theme.active().midGray()) + " " + Coord.module(coord);
    }

    /**
     * Dim italic {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime},
     * naming the time the engine was suspended when it was ({@link RunSuspensions}).
     */
    static @Nullable String elapsedSince(long startNanos) {
        return DurationText.took(Duration.ofMillis(elapsedMsSince(startNanos)), RunSuspensions.wallMs());
    }

    /**
     * Milliseconds of work since a {@link System#nanoTime} start — the unit every workspace event
     * uses: what the monotonic clock moved while the engine was suspended does not count.
     */
    static long elapsedMsSince(long startNanos) {
        return Math.max(0, (Clock.SYSTEM.nanos() - startNanos) / 1_000_000 - RunSuspensions.monoMs());
    }

    /** The phrase every settle line uses for a run that did no work: build, test, install and guard alike. */
    static final String UP_TO_DATE = "Build up to date";

    /** The green {@code Build successful} lead that opens every build success message. */
    static String buildOk() {
        return Theme.paint("Build successful", Theme.active().success());
    }

    /**
     * The one shape of a settle line: a lead, then a capitalised fragment as its own sentence,
     * {@code Build successful. Built 3 modules}. The caller appends the {@code took …}.
     */
    static String settle(String lead, String fragment) {
        return lead + ". " + fragment;
    }

    /**
     * {@link #UP_TO_DATE}, with what it covered in parentheses when that carries information:
     * {@code Build up to date (12 modules checked)}. {@code covered} null or blank is the bare phrase.
     */
    static String upToDate(@Nullable String covered) {
        return covered == null || covered.isBlank() ? UP_TO_DATE : UP_TO_DATE + " (" + covered + ")";
    }

    /** {@code 3 modules} with the count bold-white; {@code 1 module}. */
    static String modules(int n) {
        return Theme.paint(String.valueOf(n), Theme.active().focused()) + " module" + (n == 1 ? "" : "s");
    }

    /**
     * Workspace success wedge, through {@link #settle}:
     *
     * <ul>
     * <li>nothing entered → {@code Build successful. Build up to date}
     * <li>entered modules, none did productive work → {@code Build successful. Build up to date (N modules checked)}
     * <li>productive work → {@code Build successful. Built K modules}, and {@code , M up to date} for the rest
     * </ul>
     *
     * @param result this run's result; its modules may be empty on the fully-cached shortcut
     * @param planned entered-module count from the execute plan (0 when fully cached)
     * @param start wall-clock start, {@link System#nanoTime}
     */
    static String successTail(WorkspaceResult result, int planned, long start) {
        int built = 0;
        int checked = 0;
        for (var m : result.modules()) {
            if (!m.success()) continue;
            if (m.didWork()) built++;
            else checked++;
        }
        String fragment;
        if (result.upToDate()) {
            fragment = planned == 0 || checked == 0 ? UP_TO_DATE : upToDate(modules(checked) + " checked");
        } else {
            fragment = "Built " + modules(built) + (checked == 0 ? "" : ", " + checked + " up to date");
        }
        return settle(buildOk(), fragment) + " " + elapsedSince(start);
    }

    /**
     * A standalone project's success tail: {@code Build successful. Build up to date} when the
     * project did no work ({@link WorkspaceResult#upToDate}), else {@code Build successful. Built
     * <artifact>} naming the headline output, or {@code Build successful. Built project}.
     */
    static String projectTail(WorkspaceResult result, Path projectDir) {
        if (result.upToDate()) return settle(buildOk(), UP_TO_DATE);
        ProjectInfo info = ProjectInfos.orNull(projectDir);
        String art = info == null ? "" : builtArtifact(projectDir, info);
        return settle(buildOk(), "Built " + (art.isEmpty() ? "project" : art));
    }

    /**
     * The headline artifact this build produced, as its path relative to {@code moduleRoot} in the
     * path color — the native binary/library if present, else the assembly jar, else the plain jar.
     * Empty when none exists.
     */
    static String builtArtifact(Path moduleRoot, ProjectInfo info) {
        for (String candidate :
                List.of(info.nativeBinPath(), info.nativeLibPath(), info.assemblyJarPath(), info.mainJarPath())) {
            if (candidate.isEmpty()) continue;
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p))
                return Theme.paint(relForDisplay(moduleRoot, p), Theme.active().path());
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
