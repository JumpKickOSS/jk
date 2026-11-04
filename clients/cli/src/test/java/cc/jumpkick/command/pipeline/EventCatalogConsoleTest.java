// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.JsonlDownloads;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.DownloadsView;
import cc.jumpkick.cli.tui.LiveRegion;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -O jsonl} writes exactly the records {@link EventCatalog} says it does: one workspace
 * stream carrying every event the wire can send is decoded by the CLI's own decoder into its own
 * JSONL view, and the record types that come out are the catalog's console column — every carried
 * event under its record name, and nothing for an omitted one.
 */
class EventCatalogConsoleTest {

    private static final String DIR = "/ws/a";

    @Test
    void the_console_writes_exactly_what_the_catalog_says(@TempDir Path cache) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(ProtoLifecycle.jobStart(7, "build", "/ws", 3));
        lines.add(ProtoEvents.preflight("sync", 1, 1, "synced"));
        lines.add(ProtoEvents.downloads(new TransferBoard.Snapshot(1, 1, 1, 10, 0, List.of())));
        lines.add(ProtoEvents.note("a note"));
        lines.add(ProtoEvents.invocationPhase("build", "start"));
        lines.add(ProtoEvents.planModule(DIR, "g:a", "build", 1, false));
        lines.add(ProtoEvents.planStep(DIR, "compile", "Compile", "compile"));
        lines.add(ProtoEvents.planDone(1));
        lines.add(ProtoEvents.eta(1000));
        lines.add(ProtoEvents.workspaceProgress("/ws", 1, 10, "build", 0, 1));
        lines.add(ProtoEvents.moduleStart(DIR));
        lines.add(ProtoEvents.planStart(DIR, "build", 0, 10, 1, 0, false));
        lines.add(ProtoEvents.stepStart(DIR, "compile", "compile", 1));
        lines.add(ProtoEvents.progress(DIR, "compile", 1, 1, 10, 1, 0, false));
        lines.add(ProtoEvents.tickUpdate(DIR, "compile", 1, 1, 10, 1, 0, false));
        lines.add(ProtoEvents.label(DIR, "compile", "compiling"));
        lines.add(ProtoEvents.output(DIR, "compile", "a line"));
        lines.add(ProtoEvents.warn(DIR, "compile", "w", "a warning"));
        lines.add(ProtoEvents.errorLine(DIR, "compile", "e", "an error", "", ""));
        lines.add(ProtoEvents.planDiagnostic(DIR, "compile", "e", "a diagnostic", "", ""));
        lines.add(ProtoEvents.classFinish(
                DIR, "test", new TestClassResult("g:a", "demo.AppTest", 1, 0, 0, 5, "unit", List.of())));
        lines.add(ProtoEvents.stepFinish(DIR, "compile", "compile", "SUCCESS", 5, 0));
        lines.add(ProtoEvents.planFinish(DIR, true));
        lines.add(ProtoEvents.moduleFinish(DIR, "g:a", true, 0, 5));
        lines.add(ProtoLifecycle.heartbeat(5));
        // Every type a single command streams, which a build's stream never carries: whatever the
        // workspace decoder does with one, the console must write no record for it.
        for (EventCatalog.Row row : EventCatalog.rows()) {
            if (row.wire() instanceof EventCatalog.Omitted || CARRIED_ABOVE.contains(row.type())) continue;
            lines.add("{\"" + EngineProtocol.TYPE_FIELD + "\":\"" + row.type() + "\"}");
        }
        lines.add(ProtoEvents.workspaceFinish(true, 0, List.of(), false));
        lines.add(ProtoLifecycle.jobFinish(7));

        WorkspaceRunView view =
                new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), Path.of("/ws"), null, true);
        // As CommandDispatch wires a JSON command: downloads go to stdout as sparse JSONL, closed when
        // the command ends; the build command writes the terminal once the stream has been read.
        // A region another test left registered would take the downloads before the fallback does:
        // this is a JSON command, which never has one on screen.
        LiveRegion stale = LiveRegion.active();
        if (stale != null) LiveRegion.clearActive(stale);
        String out = Capture.stdout(() -> {
            JsonlDownloads downloads = new JsonlDownloads(() -> System.out, Clock.SYSTEM);
            DownloadsView.fallback(downloads);
            try {
                decode(lines, view.headless(), cache);
                view.finishEvent(true, 5);
            } finally {
                DownloadsView.fallback(null);
                downloads.finish();
                if (stale != null) LiveRegion.setActive(stale);
            }
        });

        Set<String> written = new TreeSet<>();
        for (String line : out.lines().toList()) {
            String type = Jsonl.str(line, EngineProtocol.TYPE_FIELD);
            if (type != null) written.add(type);
        }
        Set<String> expected = new TreeSet<>();
        for (EventCatalog.Row row : EventCatalog.rows()) {
            if (row.wire() instanceof EventCatalog.Omitted) continue; // not a wire event: not in this stream
            if (row.consoleJsonl() instanceof EventCatalog.Carried carried) {
                expected.add(carried.as().isEmpty() ? row.type() : carried.as());
            }
        }
        assertThat(written)
                .as("-O jsonl records for a stream of every wire event")
                .isEqualTo(expected);
    }

    /** A job that fails: the engine's error frame ends the stream, and the console says what the catalog says. */
    @Test
    void a_failed_jobs_error_frame_is_written_as_the_catalog_says(@TempDir Path cache) throws Exception {
        List<String> lines = List.of(
                ProtoLifecycle.jobStart(7, "build", "/ws", 3),
                ProtoLifecycle.error(EngineProtocol.ERR_REQUEST_FAILED, "boom"));
        WorkspaceRunView view =
                new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), Path.of("/ws"), null, true);
        String out = Capture.stdout(() -> {
            try {
                decode(lines, view.headless(), cache);
            } catch (RuntimeException failed) {
                // A failed job surfaces to the verb as a failure; what reached stdout is the question.
            }
        });
        boolean written =
                out.lines().anyMatch(l -> EventCatalog.JSONL_ERROR.equals(Jsonl.str(l, EngineProtocol.TYPE_FIELD)));
        assertThat(written)
                .as("an error record on stdout for the job's error frame: %s", out)
                .isEqualTo(EventCatalog.carries(EventCatalog.Surface.CONSOLE_JSONL, EngineProtocol.ERROR));
    }

    /** The types the stream above sends with their real builders; the rest go as bare lines. */
    private static final Set<String> CARRIED_ABOVE = Set.of(
            EngineProtocol.JOB_QUEUED,
            EngineProtocol.JOB_START,
            EngineProtocol.JOB_FINISH,
            EngineProtocol.HEARTBEAT,
            EngineProtocol.ERROR,
            EngineProtocol.PREFLIGHT,
            EngineProtocol.DOWNLOADS,
            EngineProtocol.NOTE,
            EngineProtocol.INVOCATION_PHASE,
            EngineProtocol.PLAN_MODULE,
            EngineProtocol.PLAN_TASK,
            EngineProtocol.PLAN_DONE,
            EngineProtocol.ETA,
            EngineProtocol.WORKSPACE_PROGRESS,
            EngineProtocol.MODULE_START,
            EngineProtocol.MODULE_FINISH,
            EngineProtocol.WORKSPACE_FINISH,
            EngineProtocol.BUILDPLAN_START,
            EngineProtocol.TASK_START,
            EngineProtocol.PROGRESS,
            EngineProtocol.TICK_UPDATE,
            EngineProtocol.LABEL,
            EngineProtocol.OUTPUT,
            EngineProtocol.WARN,
            EngineProtocol.ERROR_LINE,
            EngineProtocol.BUILDPLAN_DIAGNOSTIC,
            EngineProtocol.CLASS_FINISH,
            EngineProtocol.TASK_FINISH,
            EngineProtocol.BUILDPLAN_FINISH);

    /** The CLI's workspace decoder, as a build reads the engine's stream (package-private in cli.engine). */
    private static void decode(List<String> lines, WorkspaceBuildListener listener, Path cache) {
        try {
            Class<?> decoder = Class.forName("cc.jumpkick.cli.engine.EngineEventDecoder");
            Method stream = decoder.getDeclaredMethod(
                    "streamWorkspaceEvents",
                    BufferedReader.class,
                    WorkspaceBuildListener.class,
                    Path.class,
                    SocketChannel.class);
            stream.setAccessible(true);
            stream.invoke(
                    null, new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), listener, cache, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
