// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The worker rows of {@code jk doctor}: the plugin workers the engine has installed, each with the
 * launch classpath it resolves, and whether the shelf they sit on was packaged by the engine the
 * home names.
 */
public final class DoctorWorkers {

    private DoctorWorkers() {}

    /**
     * One installed plugin worker as the engine sees it: where its jar came from, the POM its
     * launch classpath is rebuilt from, and that classpath. {@code error} is the resolution
     * failure when the engine could not rebuild it; {@code classpath} is then empty. {@code
     * refused} is the loader's reason when the jar's root descriptor is another plugin's: the jar
     * is on the shelf but not registered, so the table it should own has no owner. {@code
     * packagedBy} is the sha256 of the engine jar that shelved the worker, when its memo records
     * one — what the shelf row compares with the engine the home names.
     */
    public record Worker(
            String artifact,
            String version,
            String source,
            String jar,
            String pom,
            int declared,
            List<String> classpath,
            @Nullable String error,
            @Nullable String refused,
            @Nullable String packagedBy) {}

    /** The worker rows, or the reason there are none (engine unreachable, query refused). */
    public record Workers(List<Worker> rows, @Nullable String error) {}

    /**
     * Decode the engine's {@code workers} inventory: rows are
     * {@code artifact|version|source|jar|pom|declared|entries|error|refused|packagedBy}, classpath entries
     * {@code artifact|path}. The engine answers this the way a fork resolves, so a worker that
     * runs on the wrong jar shows it here — a Guava flavour, a stale self-installed POM shadowing
     * the published one — in one line instead of an evening.
     */
    static Workers of(DoctorCommand.WorkerProbe probe) {
        CacheInventoryAck ack;
        try {
            ack = probe.workers();
        } catch (EngineClient.EngineNotRunningException e) {
            return new Workers(List.of(), DoctorCommand.ENGINE_NOT_RUNNING);
        } catch (IOException | RuntimeException e) {
            return new Workers(List.of(), "engine query failed: " + e.getMessage());
        }
        if (ack.error() != null) return new Workers(List.of(), ack.error());
        Map<String, List<String>> classpaths = new LinkedHashMap<>();
        for (String entry : ack.entries()) {
            int bar = entry.indexOf('|');
            if (bar <= 0) continue;
            classpaths
                    .computeIfAbsent(entry.substring(0, bar), k -> new ArrayList<>())
                    .add(entry.substring(bar + 1));
        }
        List<Worker> rows = new ArrayList<>();
        for (String line : ack.lines()) {
            String[] f = line.split("\\|", 10);
            if (f.length < 9) continue;
            String error = f[7].isBlank() ? null : f[7];
            String refused = f[8].isBlank() ? null : f[8];
            String packagedBy = f.length > 9 && !f[9].isBlank() ? f[9] : null;
            rows.add(new Worker(
                    f[0],
                    f[1],
                    f[2],
                    f[3],
                    f[4],
                    parseIntOrZero(f[5]),
                    List.copyOf(classpaths.getOrDefault(f[0], List.of())),
                    error,
                    refused,
                    packagedBy));
        }
        return new Workers(List.copyOf(rows), null);
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One line per worker. {@code source} names the store repo the jar was located in: a
     * {@code jk-local} worker was installed from a checkout and shadows the published one under
     * {@code jumpkick}; {@code override} is a {@code -D} jar property. A jar the loader refused is a
     * warning carrying the loader's reason, which names the descriptor it found and the fix.
     * {@code --verbose} lists the launch classpath entry by entry.
     */
    static List<String> render(Workers workers, boolean verbose, Theme t) {
        List<String> out = new ArrayList<>();
        if (workers.error() != null) {
            out.add(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("workers", t.cyan()) + " — "
                    + workers.error());
            return out;
        }
        if (workers.rows().isEmpty()) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("workers", t.cyan())
                    + " — none installed yet");
            return out;
        }
        for (Worker w : workers.rows()) {
            String label = Theme.colorize(w.artifact(), t.cyan()) + " " + w.version();
            String from = "from " + Theme.colorize(w.source(), t.path());
            if (w.refused() != null) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + label + " " + from + " — " + w.refused());
                continue;
            }
            if (w.error() != null) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + label + " " + from
                        + " — launch classpath did not resolve: " + w.error());
                continue;
            }
            out.add(Theme.colorize("worker:  ", t.completedStep()) + " " + label + " " + from + " · POM declares "
                    + w.declared() + (w.declared() == 1 ? " dep" : " deps") + " · "
                    + w.classpath().size()
                    + (w.classpath().size() == 1 ? " entry" : " entries") + " on the launch classpath");
            if (verbose) {
                out.add("           pom " + Theme.colorize(w.pom().isEmpty() ? "(none)" : w.pom(), t.path()));
                for (String entry : w.classpath()) {
                    out.add("           " + Theme.colorize(entry, t.path()));
                }
            }
        }
        return out;
    }

    /**
     * The shelf row: whether the workers under {@code jk-local} were packaged by the engine the
     * home's pointer names ({@code pointerSha}). A shelf packaged by another engine — an
     * interrupted install, an older client, a takeover that raced — has no other standing symptom,
     * and {@code jk install} is what brings it to the live engine. No row when no shelved worker
     * records its packager or the home names no engine.
     */
    static List<String> renderShelf(Workers workers, Optional<String> pointerSha, Theme t) {
        if (workers.error() != null || pointerSha.isEmpty()) return List.of();
        Map<String, List<String>> byEngine = new LinkedHashMap<>();
        for (Worker w : workers.rows()) {
            if (w.packagedBy() == null || !RepositorySpec.JK_LOCAL.equals(w.source())) continue;
            byEngine.computeIfAbsent(w.packagedBy().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(w.artifact());
        }
        if (byEngine.isEmpty()) return List.of();
        String live = pointerSha.get().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (byEngine.size() == 1 && byEngine.containsKey(live)) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("shelf", t.cyan())
                    + " — built by the installed engine (" + DoctorCommand.short12(live) + ")");
            return out;
        }
        for (Map.Entry<String, List<String>> e : byEngine.entrySet()) {
            if (e.getKey().equals(live)) continue;
            int n = e.getValue().size();
            out.add(Theme.colorize("warn:    ", t.warning()) + " " + Theme.colorize("shelf", t.cyan()) + " — " + n
                    + (n == 1 ? " worker" : " workers") + " built by engine " + DoctorCommand.short12(e.getKey())
                    + ", not the installed " + DoctorCommand.short12(live) + " (" + String.join(", ", e.getValue())
                    + "); run `jk install`");
        }
        return out;
    }

    /** The {@code workers} member of the JSON report: an array of worker objects, or an error string. */
    static String json(Workers workers) {
        if (workers.error() != null) {
            return JsonFields.object().string("error", workers.error()).finish();
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < workers.rows().size(); i++) {
            Worker w = workers.rows().get(i);
            if (i > 0) array.append(',');
            array.append(JsonFields.object()
                    .string("artifact", w.artifact())
                    .string("version", w.version())
                    .string("source", w.source())
                    .string("jar", w.jar())
                    .string("pom", w.pom())
                    .number("declared", w.declared())
                    .array("classpath", w.classpath())
                    .string("error", w.error())
                    .string("refused", w.refused())
                    .string("packagedBy", w.packagedBy())
                    .finish());
        }
        return array.append(']').toString();
    }
}
