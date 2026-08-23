// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Icon;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.LiveRegion;
import cc.jumpkick.cli.tui.PlainAscii;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Tree;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import cc.jumpkick.terminal.Ansi;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One live {@code IDE} CommandWedge for {@code jk ide}. The chip name stays {@code IDE}; {@link
 * #phase} replaces the message on the same line (Sync → JetBrains IDEA → VS Code → BSP). Generator
 * details accumulate under the chip (newest group on top). Notes are committed above the live
 * region so they survive the next rewrite. {@link #succeed} wipes the region and settles the green
 * chip.
 */
public final class IdeChrome implements AutoCloseable, LiveRegion {

    /** Chip label — the invoked command, not the current sub-step. */
    public static final String COMMAND = "IDE";

    private final PrintStream out;
    private final boolean animate;
    private final Object lock = new Object();

    private RichText message = RichText.empty();
    /** Newest detail group first, matching the live-plan "newest at top" convention. */
    private final List<RichText> details = new ArrayList<>();

    private int frame;
    private int linesDrawn;
    private List<String> lastLines = List.of();
    private boolean done;
    private Thread animator;

    private IdeChrome(PrintStream out, boolean animate) {
        this.out = PlainAscii.wrap(out);
        this.animate = animate;
    }

    /** Human path: live region on a TTY, notes + settle only under pipes / {@code --no-progress}. */
    public static IdeChrome start(String firstPhase) {
        return start(CliOutput.stdout(), shouldAnimate(), firstPhase);
    }

    /** Test / custom-stream seam. */
    static IdeChrome start(PrintStream out, boolean animate, String firstPhase) {
        IdeChrome chrome = new IdeChrome(out, animate);
        LiveRegion.setActive(chrome);
        CommandWedge.envelopeStart(out);
        chrome.phase(firstPhase);
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            chrome.startAnimator();
        }
        return chrome;
    }

    static boolean shouldAnimate() {
        if (!Theme.active().isAnsi()) return false;
        if (SessionContext.current().config().noProgressOr(false)) return false;
        return cc.jumpkick.cli.run.BuildPlanConsole.isInteractiveTerminal();
    }

    /** Replace the live wedge message. Does not start a new chip. */
    public void phase(String next) {
        phase(next == null || next.isBlank() ? RichText.empty() : RichText.plain(next));
    }

    public void phase(RichText next) {
        synchronized (lock) {
            if (done) return;
            message = next == null ? RichText.empty() : next;
            if (animate && Theme.active().isAnsi()) paint();
        }
    }

    /**
     * Prepend this generator's detail rows (preserving their order) so the latest IDE sits at the
     * top of the live tree.
     */
    public void addDetails(List<RichText> newest) {
        if (newest == null || newest.isEmpty()) return;
        synchronized (lock) {
            if (done) return;
            details.addAll(0, newest);
            if (animate && Theme.active().isAnsi()) paint();
        }
    }

    /** Commit a {@code ‼ Note:} line above the live region so later phase rewrites leave it. */
    public void note(RichText body) {
        if (body == null || body.isEmpty()) return;
        String line = formatNote(body);
        synchronized (lock) {
            if (done) {
                out.println(line);
                out.flush();
                return;
            }
            if (animate && Theme.active().isAnsi()) {
                wipe();
                out.println(line);
                lastLines = List.of();
                linesDrawn = 0;
                paint();
                return;
            }
            out.println(line);
            out.flush();
        }
    }

    /** Wipe the live region and print the green settle chip. */
    public void succeed(RichText tail) {
        settle(JkWedge.ok(COMMAND, tail == null ? RichText.empty() : tail), false);
    }

    /** Wipe the live region and print the red fail chip on stderr. */
    public void fail(String tail) {
        settle(JkWedge.fail(COMMAND, tail == null ? "" : tail), true);
    }

    /** Wipe without a settle chip — the caller already printed a failure. */
    public void dismiss() {
        settle(null, false);
    }

    /** One follow-up for every IDE we just wrote — a reload picks up project files and LS state. */
    public static RichText restartNote() {
        return RichText.parse("You may need to [italic]restart your IDE[/] for changes to take effect");
    }

    /** {@code The [bright-cyan bold]name[/] project is ready} — final settle markup. */
    public static RichText projectReady(String name) {
        String safe = RichText.escape(name == null ? "" : name);
        return RichText.parse("The [bright-cyan bold]" + safe + "[/] project is ready");
    }

    /** {@code JetBrains IDEA: The hello-http project is ready} — in-flight phase tail. */
    public static String phaseReady(String label, String name) {
        return label + ": The " + (name == null ? "" : name) + " project is ready";
    }

    /** {@code BSP: Wrote .bsp/jk.json} — workspace-relative, never absolute. */
    public static RichText bspWrote(Path connectionFile, Path relativeTo) {
        String rel = cc.jumpkick.cli.PathDisplay.of(connectionFile, relativeTo);
        return RichText.parse("BSP: Wrote [path]" + RichText.escape(rel) + "[/]");
    }

    /** Snapshot of the live tree (wedge + children). Package-private for tests. */
    List<String> renderLines(int frameNow) {
        RenderContext ctx = RenderContext.current().withFrame(frameNow);
        JkWedge title = new JkWedge(Icon.spinner(), COMMAND, message).variant(JkWedge.Variant.WORK);
        Tree tree = new Tree(title).gap(Tree.Gap.NONE);
        RichText check = RichText.parse("[success]" + Glyphs.check() + "[/] ");
        for (RichText detail : details) {
            tree.child(Tree.node(check.plus(detail)));
        }
        return tree.render(ctx);
    }

    static String formatNote(RichText body) {
        Theme t = Theme.active();
        String head = Theme.colorize(Glyphs.bang() + " Note", t.warning());
        return " " + head + ": " + body.render(RenderContext.current());
    }

    private void settle(JkWedge chip, boolean stderr) {
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (animate && Theme.active().isAnsi()) {
                wipe();
                out.print(Osc.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            }
            if (chip != null) {
                String line = chip.renderLine(RenderContext.current());
                if (stderr) {
                    CliOutput.err(line); // opens the stderr envelope itself
                } else {
                    CommandWedge.envelopeStart(out);
                    out.println(line);
                }
            }
            out.flush();
        }
    }

    private void startAnimator() {
        animator = new Thread(this::loop, "jk-ide-chrome");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        while (!done) {
            tick();
            try {
                Thread.sleep(WorkspaceProgressTracker.TTY_FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void tick() {
        synchronized (lock) {
            if (done || !animate || !Theme.active().isAnsi()) return;
            frame++;
            paint();
        }
    }

    /** Must hold {@link #lock}. */
    private void paint() {
        List<String> lines = renderLines(frame);
        int prev = lastLines.size();
        if (prev > 0) out.print(Ansi.cursorUp(prev));
        for (int i = 0; i < lines.size(); i++) {
            boolean changed = i >= prev || !lines.get(i).equals(lastLines.get(i));
            if (changed) {
                out.print('\r');
                out.print(lines.get(i));
                out.print(Ansi.ERASE_LINE_TO_END);
            }
            out.print('\n');
        }
        if (prev > lines.size()) out.print(Ansi.ERASE_DISPLAY_TO_END);
        out.print(Osc.taskbarIndeterminate());
        out.flush();
        lastLines = lines;
        linesDrawn = lines.size();
    }

    /** Must hold {@link #lock}. */
    private void wipe() {
        if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
        out.print('\r');
        out.print(Ansi.ERASE_DISPLAY_TO_END);
        linesDrawn = 0;
        lastLines = List.of();
    }

    private void stopAnimator() {
        if (animator != null) animator.interrupt();
    }

    @Override
    public String canceledMessage() {
        return "IDE generation canceled by user";
    }

    @Override
    public boolean renderCanceled() {
        stopAnimator();
        synchronized (lock) {
            if (done) return true;
            done = true;
            LiveRegion.clearActive(this);
            if (animate && Theme.active().isAnsi()) {
                wipe();
                out.print(Osc.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
                out.println(JkWedge.fail(COMMAND, "canceled by user").renderLine(RenderContext.current()));
                out.flush();
                return true;
            }
            out.flush();
            return false;
        }
    }

    @Override
    public void close() {
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (animate && Theme.active().isAnsi()) {
                wipe();
                out.print(Osc.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            }
            out.flush();
        }
    }
}
