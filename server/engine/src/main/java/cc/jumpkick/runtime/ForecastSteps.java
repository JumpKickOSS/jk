// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathProcessors;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.host.Log;
import cc.jumpkick.runtime.TaskForecaster.DepHint;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How a forecast prices one step: the {@link TaskForecast.Task} a javac prediction or a
 * stamp-language action key maps to, the text that names why it runs, and whether an action
 * record still restores from the cache. {@link TaskForecaster} walks the graph; this is what it
 * says about each step.
 */
final class ForecastSteps {

    private ForecastSteps() {}

    /**
     * The request the forecast keys compile-main with, as a perf note beside the live step's
     * {@code live-compile-main}: the two lines diff when the forecast and the build disagree.
     */
    static void noteCompileMain(
            Path out, CompileRequest req, int sources, int release, boolean stampFresh, boolean depDirty) {
        if (!Perf.enabled()) return;
        try {
            Perf.note(
                    "forecast-compile-main " + out,
                    "optionsDigest",
                    ActionKey.javacOptionsDigest(req),
                    "cp",
                    req.classpath().size(),
                    "pp",
                    req.processorPath().size(),
                    "src",
                    sources,
                    "release",
                    release,
                    "javaHome",
                    req.javaHome(),
                    "stampFresh",
                    stampFresh,
                    "depDirty",
                    depDirty,
                    "pp-list",
                    req.processorPath());
        } catch (IOException e) {
            Log.debug("noteCompileMain: the options digest could not be read", e);
        }
    }

    /**
     * Map a {@link JavaCompile.Prediction} to a step, honoring upstream dirtiness. A request that
     * invokes javac plugins names them in the step's text, so {@code jk explain --verbose} shows
     * that input beside the outcome.
     */
    static TaskForecast.Task compileStep(
            String name, JavaCompile.Prediction pred, boolean compileDepDirty, CompileRequest request) {
        return compileStep(name, pred, compileDepDirty, request, DepHint.NONE);
    }

    /**
     * As above with the consumer's {@link DepHint}: the text a dependency-dirty hit carries. The
     * step then names the javac plugins the request invokes and the annotation processors on its
     * processor path — each processor class with the jar it comes from — so {@code jk explain
     * --verbose} shows what the compile runs beside the sources it compiles.
     */
    static TaskForecast.Task compileStep(
            String name, JavaCompile.Prediction pred, boolean compileDepDirty, CompileRequest request, DepHint hint) {
        TaskForecast.Task step = compileStep(name, pred, compileDepDirty, hint);
        List<String> parts = new ArrayList<>();
        if (!step.text().isEmpty()) parts.add(step.text());
        List<String> plugins = PlannerCompile.pluginNames(request);
        if (!plugins.isEmpty()) parts.add(PlannerCompile.PLUGIN_FLAG + String.join(",", plugins));
        String processors = processorsText(request.processorPath());
        if (!processors.isEmpty()) parts.add(processors);
        if (parts.size() == (step.text().isEmpty() ? 0 : 1)) return step;
        return new TaskForecast.Task(name, step.status(), String.join(" · ", parts), step.key());
    }

    /** {@code processors: a.b.Gen (gen.jar), c.d.Mapper (mapper-1.0.jar)}, or {@code ""} when the path registers none. */
    static String processorsText(List<Path> processorPath) {
        List<String> named = new ArrayList<>();
        for (Path entry : processorPath) {
            for (String processor : ClasspathProcessors.processorNames(entry)) {
                named.add(processor + " (" + entry.getFileName() + ")");
            }
        }
        return named.isEmpty() ? "" : "processors: " + String.join(", ", named);
    }

    /** The text of a hit whose compile-scope dependency is rebuilding: the hint when there is one. */
    static String dependencyChanged(DepHint hint) {
        return hint.text().isEmpty() ? "recompile · dependency changed" : hint.text();
    }

    private static TaskForecast.Task compileStep(
            String name, JavaCompile.Prediction pred, boolean compileDepDirty, DepHint hint) {
        return switch (pred.outcome()) {
            case CACHE_HIT ->
                // Only force RUN when a *compile-scope* sibling is dirty: the key was computed
                // against the sibling's current output, so the step stays scheduled and the build's
                // own key decides. The hint says which way that is likely to go. Test-only siblings
                // never reach here as compileDepDirty.
                compileDepDirty
                        ? new TaskForecast.Task(name, TaskForecast.Status.RUN, dependencyChanged(hint), null)
                        : new TaskForecast.Task(name, TaskForecast.Status.CACHED, "", key8(pred.actionKey()));
            case INCREMENTAL -> {
                String detail = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : count(pred.sourceCount(), "source") + " changed";
                String files = fileHint(pred.sources());
                if (!files.isEmpty()) detail = detail + " (" + files + ")";
                yield new TaskForecast.Task(name, TaskForecast.Status.PARTIAL, "compile · " + detail, null);
            }
            case FULL -> {
                // surface the concrete gate (classpath, options, first compile, …).
                String why = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : "sources / options / classpath";
                yield new TaskForecast.Task(
                        name,
                        TaskForecast.Status.FULL,
                        "full compile · " + count(pred.sourceCount(), "source") + " · " + why,
                        null);
            }
        };
    }

    /**
     * Why a stamp-language compile's key misses, read off the task's last record the way {@code
     * JavaCompile.predict} reads javac's: the inputs both records spell — sources by content,
     * {@code java-api:} declarations, {@code cp:} ABI tokens, {@code pp:} processors, {@code
     * worker:} compiler — compared line by line, so a body-only dependency rewrite reads as no
     * classpath change and an API change names the entry. Empty when there is no prior record or
     * only the option-bearing lines moved.
     */
    static String langMissReason(ActionCache ac, String taskId, Map<String, String> now) {
        Map<String, String> prior;
        try {
            var record = ac.lastFor(taskId);
            if (record.isEmpty()) return "";
            prior = record.get().inputs();
        } catch (IOException e) {
            return "";
        }
        int sources = 0;
        List<String> declarations = new ArrayList<>();
        List<String> api = new ArrayList<>();
        List<String> processors = new ArrayList<>();
        boolean compiler = false;
        Set<String> keys = new HashSet<>(now.keySet());
        keys.addAll(prior.keySet());
        for (String k : keys) {
            if (Objects.equals(now.get(k), prior.get(k))) continue;
            if (k.startsWith("cp:")) api.add(fileName(k.substring(3)));
            else if (k.startsWith("pp:")) processors.add(fileName(k.substring(3)));
            else if (k.startsWith("java-api:")) declarations.add(fileName(k.substring("java-api:".length())));
            else if (k.startsWith("worker:")) compiler = true;
            else if (k.indexOf('/') >= 0 || k.indexOf('\\') >= 0) sources++;
        }
        List<String> parts = new ArrayList<>();
        if (sources > 0) parts.add(count(sources, "source") + " changed");
        if (!declarations.isEmpty()) parts.add("Java declarations changed (" + String.join(", ", declarations) + ")");
        if (!api.isEmpty()) parts.add("dependency ABI changed (" + String.join(", ", api) + ")");
        if (!processors.isEmpty()) parts.add("processor changed (" + String.join(", ", processors) + ")");
        if (compiler) parts.add("compiler changed");
        return String.join(" · ", parts);
    }

    static String fileName(String path) {
        Path name = Path.of(path).getFileName();
        return name == null ? path : name.toString();
    }

    /**
     * A stamp-language compile step ({@code compile-kotlin}, {@code compile-groovy}) priced by its
     * action key, the way {@link #compileStep} prices javac's: CACHED when the key's record is
     * present with its payloads, RUN when a compile-scope sibling is rebuilding (the key was
     * computed against that sibling's current output and cannot be trusted), else a full compile
     * — these workers have no incremental plan to consult — with {@code missReason} beside it.
     */
    static TaskForecast.Task langCompileStep(
            String name,
            boolean hit,
            String key,
            int sourceCount,
            boolean compileDepDirty,
            String missReason,
            DepHint hint) {
        if (hit && compileDepDirty) {
            return new TaskForecast.Task(name, TaskForecast.Status.RUN, dependencyChanged(hint), null);
        }
        if (hit) return new TaskForecast.Task(name, TaskForecast.Status.CACHED, "", key8(key));
        String text = "full compile · " + count(sourceCount, "source");
        if (!missReason.isBlank()) text = text + " · " + missReason;
        return new TaskForecast.Task(name, TaskForecast.Status.FULL, text, null);
    }

    /**
     * Record exists AND every <em>payload</em> blob is still in the action cache's CAS. LRU
     * eviction removes payloads while their records live on (records die by TTL), and a record
     * whose blobs are gone cannot restore — forecasting it CACHED would over-promise: wrong
     * {@code jk explain}, undercounted dirty set, deflated ETA seed.
     *
     * <p>Only 64-char hex values are payload digests. Marker records (run-tests green stamp)
     * park small scalars such as {@code tests.total=0} in the same map — those are not CAS
     * keys and must not fail the presence check, or a successful empty/green suite is forever
     * forecast as dirty (plan shows {@code run-tests [run]} after every build).
     *
     * <p>Presence check only ({@code pathFor} + {@code isRegularFile}); never hashes bytes.
     */
    static boolean present(ActionCache ac, @Nullable String key) {
        return presentRecord(ac, key).isPresent();
    }

    /** The record behind {@link #present}, for callers that read its markers. */
    static Optional<ActionCache.ActionRecord> presentRecord(ActionCache ac, @Nullable String key) {
        try {
            if (key == null) return Optional.empty();
            var rec = ac.lookup(key);
            if (rec.isEmpty()) return Optional.empty();
            for (String sha : rec.get().outputs().values()) {
                if (!isSha256Hex(sha)) continue; // marker scalar, not a CAS blob
                if (!Files.isRegularFile(ac.cas().pathFor(sha))) return Optional.empty();
            }
            return rec;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Same shape {@link ActionCache} meters by — 64-char hex digests only. */
    static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static String key8(String key) {
        return key != null && key.length() >= 8 ? key.substring(0, 8) : key;
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String fileHint(List<Path> sources) {
        if (sources == null || sources.isEmpty()) return "";
        int n = sources.size();
        int show = Math.min(n, 4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < show; i++) {
            if (i > 0) b.append(", ");
            b.append(sources.get(i).getFileName());
        }
        if (n > show) b.append(", +").append(n - show);
        return b.toString();
    }

    /**
     * What this module's edit looks like to its consumers, before it compiles: its own Java sources
     * classified against the declaration baseline its last compile left ({@link SourceApiIndex}),
     * folded with what its own dirty dependencies look like — a constant copied from a dependency
     * whose API moved moves this module's API too. Unknown whenever the answer needs a compile:
     * a forced rebuild, an option or release change, a source the baseline does not describe.
     * {@code force} and {@code compileDepDirty} are the module's forecast flags; {@code depHint} what
     * its dirty dependencies look like.
     */
    static SourceApiIndex.Hint ownApiHint(
            boolean force,
            boolean compileDepDirty,
            DepHint depHint,
            Path dir,
            ModuleForecast.Prepared prepared,
            JavaCompile.Prediction pred) {
        if (force) return SourceApiIndex.Hint.UNKNOWN;
        if (pred.outcome() == JavaCompile.Outcome.CACHE_HIT) {
            return compileDepDirty
                    ? new SourceApiIndex.Hint(depHint.kind(), List.of())
                    : new SourceApiIndex.Hint(SourceApiIndex.Kind.BODY_ONLY, List.of());
        }
        String reason = pred.reason();
        if (reason.contains("options changed") || reason.contains("release changed")) {
            return SourceApiIndex.Hint.UNKNOWN;
        }
        SourceApiIndex.Hint own;
        try {
            // A processor may shape public output from a private member; without one, private
            // members are invisible to every consumer and their edits are body-only.
            own = SourceApiIndex.classify(
                    dir,
                    SourceApiIndex.load(SourceApiIndex.path(prepared.layout().buildDir())),
                    prepared.mainSrc(),
                    !prepared.processorCp().isEmpty());
        } catch (IOException e) {
            Log.debug("ownApiHint: no baseline", e);
            return SourceApiIndex.Hint.UNKNOWN;
        }
        if (own.kind() == SourceApiIndex.Kind.UNKNOWN) return own;
        if (compileDepDirty && depHint.kind() == SourceApiIndex.Kind.UNKNOWN) return SourceApiIndex.Hint.UNKNOWN;
        if (compileDepDirty && depHint.kind() == SourceApiIndex.Kind.API_CHANGED) {
            return new SourceApiIndex.Hint(SourceApiIndex.Kind.API_CHANGED, own.files());
        }
        return own;
    }
}
