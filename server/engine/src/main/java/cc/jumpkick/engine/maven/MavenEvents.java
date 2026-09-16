// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The spy's event file: one line per event of eight tab-separated, percent-encoded fields — the
 * {@code ExecutionEvent.Type} name, the module's wall millis from Maven's build summary (project
 * events only), {@code project}, {@code dir}, {@code goal}, {@code execution}, {@code exception}
 * and {@code message}. Folded per module into an outcome, a mojo step chain and the first failed
 * mojo.
 */
public final class MavenEvents {

    private MavenEvents() {}

    /** One line of the event file; absent fields read as {@code ""}. */
    public record Event(
            String type,
            long millis,
            String project,
            String dir,
            String goal,
            String execution,
            String exception,
            String message) {}

    /** One mojo execution: {@code status} is {@code SUCCESS}, {@code FAIL} or {@code SKIPPED}. */
    public record Step(String goal, String status) {}

    /** The first mojo that failed in a module, with what it threw. */
    public record Failure(String goal, String exception, String message) {}

    /** One reactor module: {@code outcome} is {@code SUCCESS}, {@code FAIL} or {@code SKIPPED}. */
    public record Module(
            String coord,
            String dir,
            String outcome,
            long millis,
            List<Step> steps,
            @Nullable Failure failure) {
        public boolean success() {
            return "SUCCESS".equals(outcome);
        }

        public boolean skipped() {
            return "SKIPPED".equals(outcome);
        }
    }

    /** The field count of a well-formed line. */
    static final int FIELDS = 8;

    /** Every well-formed line, in file order; a missing file is an empty run. */
    public static List<Event> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<Event> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Event e = parse(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** One line, or {@code null} when it is not eight decodable fields with a type. */
    static @Nullable Event parse(String line) {
        String[] raw = line.split("\t", -1);
        if (raw.length != FIELDS) return null;
        String[] f = new String[FIELDS];
        try {
            for (int i = 0; i < FIELDS; i++) f[i] = URLDecoder.decode(raw[i], StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (f[0].isBlank()) return null;
        return new Event(f[0], millis(f[1]), f[2], f[3], f[4], f[5], f[6], f[7]);
    }

    private static long millis(String s) {
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Modules in reactor order, each with the steps its mojo events describe. */
    public static List<Module> modules(List<Event> events) {
        Map<String, Folding> byDir = new LinkedHashMap<>();
        for (Event e : events) {
            if (e.dir().isEmpty()) continue;
            Folding f = byDir.computeIfAbsent(e.dir(), d -> new Folding(e.project(), d));
            f.take(e);
        }
        List<Module> out = new ArrayList<>(byDir.size());
        for (Folding f : byDir.values()) out.add(f.module());
        return out;
    }

    /** Maven's log prefix for a plugin: {@code maven-compiler-plugin:compile} is {@code compiler:compile}. */
    static String shortGoal(String goal) {
        int colon = goal.indexOf(':');
        if (colon <= 0) return goal;
        String plugin = goal.substring(0, colon);
        if (plugin.startsWith("maven-") && plugin.endsWith("-plugin")) {
            plugin = plugin.substring("maven-".length(), plugin.length() - "-plugin".length());
        } else if (plugin.endsWith("-maven-plugin")) {
            plugin = plugin.substring(0, plugin.length() - "-maven-plugin".length());
        }
        return plugin + goal.substring(colon);
    }

    /** One module's events, in arrival order. */
    private static final class Folding {
        private final String coord;
        private final String dir;
        private String outcome = "SUCCESS";
        private long millis;
        private final List<Step> steps = new ArrayList<>();
        private @Nullable Failure failure;

        Folding(String coord, String dir) {
            this.coord = coord;
            this.dir = dir;
        }

        void take(Event e) {
            switch (e.type()) {
                case "ProjectSucceeded" -> millis = e.millis();
                case "ProjectFailed" -> {
                    millis = e.millis();
                    outcome = "FAIL";
                    if (failure == null && !e.message().isEmpty())
                        failure = new Failure("", e.exception(), e.message());
                }
                case "ProjectSkipped" -> outcome = "SKIPPED";
                case "MojoSucceeded" -> steps.add(new Step(shortGoal(e.goal()), "SUCCESS"));
                case "MojoSkipped" -> steps.add(new Step(shortGoal(e.goal()), "SKIPPED"));
                case "MojoFailed" -> {
                    steps.add(new Step(shortGoal(e.goal()), "FAIL"));
                    Failure f = new Failure(shortGoal(e.goal()), e.exception(), e.message());
                    if (failure == null || failure.goal().isEmpty()) failure = f;
                }
                default -> {}
            }
        }

        Module module() {
            return new Module(coord, dir, outcome, millis, List.copyOf(steps), failure);
        }
    }
}
