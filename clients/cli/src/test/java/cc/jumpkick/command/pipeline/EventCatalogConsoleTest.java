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
import cc.jumpkick.wire.protocol.ClassFinishEvent;
import cc.jumpkick.wire.protocol.DownloadsEvent;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ErrorLineEvent;
import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.EventEnvelope;
import cc.jumpkick.wire.protocol.LabelEvent;
import cc.jumpkick.wire.protocol.ModuleFinishEvent;
import cc.jumpkick.wire.protocol.ModuleStartEvent;
import cc.jumpkick.wire.protocol.NoteEvent;
import cc.jumpkick.wire.protocol.OutputEvent;
import cc.jumpkick.wire.protocol.PlanFinishEvent;
import cc.jumpkick.wire.protocol.PlanStartEvent;
import cc.jumpkick.wire.protocol.PreflightEvent;
import cc.jumpkick.wire.protocol.ProgressEvent;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.protocol.TaskFinishEvent;
import cc.jumpkick.wire.protocol.TaskStartEvent;
import cc.jumpkick.wire.protocol.TickUpdateEvent;
import cc.jumpkick.wire.protocol.WarnEvent;
import cc.jumpkick.wire.protocol.WorkspaceFinishEvent;
import cc.jumpkick.wire.protocol.WorkspaceProgressEvent;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        eachLineIsItsRecord(lines, out);
    }

    /**
     * Each console line is its event's wire record under the console envelope: every field it
     * writes is one the record defines, spelled as the record spells it, and every field the
     * engine sent is written but those the console drops on purpose.
     */
    private static void eachLineIsItsRecord(List<String> sent, String out) throws ReflectiveOperationException {
        Map<String, Set<String>> defined = new HashMap<>();
        for (Class<?> record : RECORDS) {
            String line = (String) record.getMethod("encode").invoke(populated(record));
            String type = Objects.requireNonNull(EngineProtocol.typeOf(line), line);
            defined.computeIfAbsent(consoleName(type), k -> new TreeSet<>()).addAll(EventEnvelope.keys(line));
        }
        Map<String, Set<String>> writtenKeys = new HashMap<>();
        for (String line : out.lines().toList()) {
            String type = Objects.requireNonNull(Jsonl.str(line, EngineProtocol.TYPE_FIELD), line);
            Set<String> keys = new TreeSet<>(EventEnvelope.keys(line));
            keys.removeAll(ENVELOPE);
            writtenKeys.computeIfAbsent(type, k -> new TreeSet<>()).addAll(keys);
            if (!defined.containsKey(type)) continue; // a record only the CLI writes
            assertThat(defined.get(type))
                    .as("`%s` on -O jsonl writes only its record's fields: %s", type, line)
                    .containsAll(keys);
        }
        for (String line : sent) {
            String type = Objects.requireNonNull(EngineProtocol.typeOf(line), line);
            if (!EventCatalog.carries(EventCatalog.Surface.CONSOLE_JSONL, type)) continue;
            // An event the console writes as a record of its own (plan-done's workspace-start) is
            // not a projection of what the engine sent.
            if (!defined.containsKey(consoleName(type))) continue;
            Set<String> keys = new TreeSet<>(EventEnvelope.keys(line));
            keys.removeAll(ENVELOPE);
            keys.removeAll(DROPPED.getOrDefault(type, Set.of()));
            assertThat(writtenKeys.getOrDefault(consoleName(type), Set.of()))
                    .as("every field the engine sent on `%s` reaches -O jsonl under its own name: %s", type, line)
                    .containsAll(keys);
        }
    }

    private static String consoleName(String type) {
        EventCatalog.Row row = EventCatalog.row(type);
        if (row != null
                && row.consoleJsonl() instanceof EventCatalog.Carried carried
                && !carried.as().isEmpty()) {
            return carried.as();
        }
        return type;
    }

    /** The console envelope and its riders: {@code module} names a step's module, {@code progress} is the run's. */
    private static final Set<String> ENVELOPE = Set.of("schema", "ts", "type", "module", "progress");

    /** What the console leaves out of a record on purpose. */
    private static final Map<String, Set<String>> DROPPED = Map.ofEntries(
            Map.entry(EngineProtocol.TASK_START, Set.of("dir")),
            Map.entry(EngineProtocol.TASK_FINISH, Set.of("dir")),
            Map.entry(EngineProtocol.LABEL, Set.of("dir")),
            Map.entry(EngineProtocol.OUTPUT, Set.of("dir")),
            Map.entry(EngineProtocol.WARN, Set.of("dir")),
            Map.entry(EngineProtocol.ERROR_LINE, Set.of("dir")),
            Map.entry(EngineProtocol.BUILDPLAN_DIAGNOSTIC, Set.of("dir")),
            Map.entry(EngineProtocol.CLASS_FINISH, Set.of("dir")),
            Map.entry(EngineProtocol.BUILDPLAN_START, Set.of("dir")),
            Map.entry(EngineProtocol.BUILDPLAN_FINISH, Set.of("dir")),
            Map.entry(EngineProtocol.PROGRESS, Set.of("dir")),
            Map.entry(EngineProtocol.TICK_UPDATE, Set.of("dir")),
            Map.entry(EngineProtocol.DOWNLOADS, Set.of("rows")));

    /** The wire records of every event the console carries. */
    private static final List<Class<?>> RECORDS = List.of(
            ClassFinishEvent.class,
            DownloadsEvent.class,
            ErrorLineEvent.class,
            EtaEvent.class,
            LabelEvent.class,
            ModuleFinishEvent.class,
            ModuleStartEvent.class,
            NoteEvent.class,
            OutputEvent.class,
            PlanFinishEvent.class,
            PlanStartEvent.class,
            PreflightEvent.class,
            ProgressEvent.class,
            TaskFinishEvent.class,
            TaskStartEvent.class,
            TickUpdateEvent.class,
            WarnEvent.class,
            WorkspaceFinishEvent.class,
            WorkspaceProgressEvent.class);

    /** A record with every component set, so its encoding spells every field it can. */
    private static Object populated(Class<?> type) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = value(types[i]);
        }
        Constructor<?> ctor = type.getDeclaredConstructor(types);
        return ctor.newInstance(values);
    }

    private static Object value(Class<?> type) throws ReflectiveOperationException {
        if (type == String.class) return "x";
        if (type == boolean.class) return true;
        if (type == int.class) return 1;
        if (type == long.class) return 1L;
        if (type == double.class) return 1.0;
        if (type == List.class) return List.of();
        if (type == Path.class) return Path.of("/x");
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type.isRecord()) return populated(type);
        throw new IllegalArgumentException("no value for " + type);
    }

    /**
     * A job the engine fails ({@code ERR_REQUEST_FAILED}) through the CLI's own decoder and its
     * headless ladder, as {@code -O jsonl} runs it: the stream opens with the job's {@code job}
     * line and ends with {@code workspace-finish}, whose {@code errors} say why.
     */
    @Test
    void a_failed_jobs_reason_is_in_the_terminals_errors(@TempDir Path cache) throws Exception {
        List<String> lines = List.of(
                ProtoLifecycle.jobStart(7, "build", "/ws", 3),
                ProtoEvents.preflight("lock", 1, 1, "3 module(s) dirty"),
                ProtoLifecycle.error(EngineProtocol.ERR_REQUEST_FAILED, "jk.toml: unknown key `bogus`"),
                ProtoLifecycle.jobFinish(7));
        WorkspaceRunView view =
                new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), Path.of("/ws"), null, true);
        String out = Capture.stdout(() -> {
            WorkspaceResult result = decode(lines, view.headless(), cache);
            view.settleHeadless(result, 5, new WorkspaceRunView.Tails((r, n) -> "ok", r -> "failed"), settled -> {});
        });

        List<String> types =
                out.lines().map(l -> Jsonl.str(l, EngineProtocol.TYPE_FIELD)).toList();
        assertThat(types.getFirst())
                .as("the job line opens the stream: %s", out)
                .isEqualTo(EventCatalog.JSONL_JOB);
        assertThat(types).contains(EngineProtocol.PREFLIGHT);
        assertThat(types.getLast()).isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        String finish = out.lines().toList().getLast();
        assertThat(Jsonl.bool(finish, "success", true)).isFalse();
        assertThat(Jsonl.strArray(finish, "errors")).containsExactly("jk.toml: unknown key `bogus`");
        assertThat(Jsonl.longValue(out.lines().toList().getFirst(), "jid", -1)).isEqualTo(7);
    }

    /** A run that fails before the engine answers (a wire error) still says why in its terminal. */
    @Test
    void a_run_that_never_got_an_answer_says_why_in_its_terminal() {
        WorkspaceRunView view =
                new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", true), Path.of("/ws"), null, true);
        String out = Capture.stdout(() -> view.finishEvent(false, 5, "jk engine: build failed: boom"));

        assertThat(Jsonl.strArray(out.strip(), "errors")).containsExactly("jk engine: build failed: boom");
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
    private static WorkspaceResult decode(List<String> lines, WorkspaceBuildListener listener, Path cache) {
        try {
            Class<?> decoder = Class.forName("cc.jumpkick.cli.engine.EngineEventDecoder");
            Method stream = decoder.getDeclaredMethod(
                    "streamWorkspaceEvents",
                    BufferedReader.class,
                    WorkspaceBuildListener.class,
                    Path.class,
                    SocketChannel.class);
            stream.setAccessible(true);
            return (WorkspaceResult) stream.invoke(
                    null, new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), listener, cache, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
