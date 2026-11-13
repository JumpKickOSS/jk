// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TaskNames;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a step kind the estimate has no history of in a module costs, from one cheap measure of its
 * effort and what that kind took on this host in every project before (JK-PMF9X). A step kind the
 * host has never seen was priced as a touch: a node install that took 6 s, an AOT run 3.5 s, a jOOQ
 * generation 1.5 s, each at 150 ms.
 *
 * <p>Judged on a cold set of fourteen unrelated projects, leaving each out in turn ({@code
 * bench/eta/features.py}), against a flat mean of the kind:
 *
 * <ul>
 *   <li>{@code node-install}: the packages its lockfile pins, priced at a per-package rate: 39%
 *       median error against 90%.
 *   <li>{@code node-build} and {@code node-test}: the tool its {@code package.json} script runs
 *       ({@code vite}, {@code next}, {@code ng}, {@code vitest}), priced at that tool's median: 6%
 *       and 12% against 55% and 78%. The amount of code said nothing the tool did not.
 *   <li>Any other kind this measures nothing of ({@code plugin-*}: AOT, code generation): the median
 *       of the kind on this host, from its first run on.
 * </ul>
 *
 * <p>Until the host has run a kind, a reference price from that cold set stands in, at the host's
 * CPU scale. Samples are kept per kind and measure, the latest {@link #KEEP} of each, in one small
 * host file, written once per finished run.
 */
public final class StepEffort {

    /** The host file's name, beside its other build metrics. */
    public static final String FILE = HostStore.FILE;

    /** Samples kept per kind and measure: recent runs only, so a faster machine or tool shows. */
    static final int KEEP = 16;

    /** A package's install time on the reference host; a node install with no lockfile is priced as 200 packages. */
    static final double REF_MS_PER_PACKAGE = 8.3;

    static final int PACKAGES_UNKNOWN = 200;

    /** The cold set's medians per tool on the reference host, and the kind's own when the tool is unknown. */
    static final Map<String, Long> REF_BUILD_MS = Map.of("vite", 540L, "ng", 2_300L, "next", 3_700L);

    static final long REF_BUILD_OTHER_MS = 1_800L;
    static final Map<String, Long> REF_TEST_MS = Map.of("vitest", 380L, "ng", 2_300L, "jest", 1_500L);
    static final long REF_TEST_OTHER_MS = 1_200L;

    /** Plugin goals the cold set ran, and any other plugin goal: an AOT run starts a JVM and a context. */
    static final Map<String, Long> REF_PLUGIN_MS = Map.of("plugin-spring-aot", 3_500L, "plugin-generate-jooq", 1_300L);

    static final long REF_PLUGIN_OTHER_MS = 1_500L;

    /** A step kind's cheap measure in a module: a count, a tool's name, or nothing it measures. */
    public record Measure(String key, long units) {
        static final Measure NONE = new Measure("", 0);
    }

    /** A price, and whether this host measured it ({@code true}) or the reference stood in. */
    public record Price(long ms, boolean learned) {}

    /** One finished step: its kind, its measure in its module, its wall. */
    public record Sample(String step, Measure measure, long wallMs) {}

    private final Map<String, List<long[]>> samples;

    private StepEffort(Map<String, List<long[]>> samples) {
        this.samples = samples;
    }

    /** The host's samples. */
    public static StepEffort load() {
        return load(defaultFile());
    }

    public static StepEffort load(Path file) {
        return new StepEffort(read(file));
    }

    public static Path defaultFile() {
        return HostStore.defaultFile();
    }

    /** Whether this prices {@code step}: the node steps and every plugin goal. */
    public static boolean prices(String step) {
        return step.equals(TaskNames.NODE_INSTALL)
                || step.equals(TaskNames.NODE_BUILD)
                || step.equals(TaskNames.NODE_TEST)
                || step.startsWith("plugin-");
    }

    /** {@code step}'s measure in the module at {@code moduleDir}. */
    public static Measure measure(String step, Path moduleDir) {
        return switch (step) {
            case TaskNames.NODE_INSTALL -> new Measure("", lockPackages(moduleDir));
            case TaskNames.NODE_BUILD -> new Measure(scriptTool(moduleDir, "build"), 0);
            case TaskNames.NODE_TEST -> new Measure(scriptTool(moduleDir, "test"), 0);
            default -> Measure.NONE;
        };
    }

    /**
     * {@code step}'s price at {@code measure}: this host's own samples of the kind when it has any,
     * else the reference price at {@code cpuScale}. Empty for a step this does not price.
     */
    public @Nullable Price price(String step, Measure measure, double cpuScale) {
        if (!prices(step)) return null;
        if (step.equals(TaskNames.NODE_INSTALL)) {
            long packages = measure.units() > 0 ? measure.units() : PACKAGES_UNKNOWN;
            OptionalLong rate = medianPerUnit(key(step, ""));
            if (rate.isPresent()) return new Price(Math.max(1, Math.round(rate.getAsLong() / 1000.0 * packages)), true);
            return new Price(scaled(Math.round(REF_MS_PER_PACKAGE * packages), cpuScale), false);
        }
        // This host's own runs of the kind with this measure (a plugin goal's is the goal itself),
        // then the cold set's price for this tool, then this host's runs of the kind by any tool,
        // then the cold set's price for the kind.
        OptionalLong same = median(key(step, measure.key()));
        if (same.isPresent()) return new Price(same.getAsLong(), true);
        Long ref = reference(step, measure.key());
        if (ref != null) return new Price(scaled(ref, cpuScale), false);
        OptionalLong any = medianOfKind(step);
        if (any.isPresent()) return new Price(any.getAsLong(), true);
        return new Price(scaled(fallback(step), cpuScale), false);
    }

    /** The reference price for {@code step} run by {@code tool}, or null when the cold set has none. */
    private static @Nullable Long reference(String step, String tool) {
        return switch (step) {
            case TaskNames.NODE_BUILD -> REF_BUILD_MS.get(tool);
            case TaskNames.NODE_TEST -> REF_TEST_MS.get(tool);
            default -> REF_PLUGIN_MS.get(step);
        };
    }

    private static long fallback(String step) {
        return switch (step) {
            case TaskNames.NODE_BUILD -> REF_BUILD_OTHER_MS;
            case TaskNames.NODE_TEST -> REF_TEST_OTHER_MS;
            default -> REF_PLUGIN_OTHER_MS;
        };
    }

    private static long scaled(long ms, double cpuScale) {
        double s = cpuScale > 0 && Double.isFinite(cpuScale) ? Math.max(0.25, Math.min(4, cpuScale)) : 1;
        return Math.max(1, Math.round(ms * s));
    }

    /** Teach {@code file} a finished run's steps of the kinds this prices; the rest are ignored. */
    public static void learn(Path file, List<Sample> run) {
        List<Sample> kept =
                run.stream().filter(s -> prices(s.step()) && s.wallMs() > 0).toList();
        if (kept.isEmpty()) return;
        HostStore.update(file, HostStore.EFFORT, lines -> {
            Map<String, List<long[]>> all = parse(lines);
            for (Sample s : kept) {
                List<long[]> list =
                        all.computeIfAbsent(key(s.step(), s.measure().key()), k -> new ArrayList<>());
                list.add(new long[] {s.measure().units(), s.wallMs()});
                while (list.size() > KEEP) list.remove(0);
            }
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, List<long[]>> e : all.entrySet()) {
                for (long[] v : e.getValue()) out.add(e.getKey() + '\t' + v[0] + '\t' + v[1]);
            }
            return out;
        });
    }

    private static String key(String step, String measure) {
        return step + "\t" + (measure.isEmpty() ? "-" : measure);
    }

    private OptionalLong median(String key) {
        List<long[]> v = samples.get(key);
        if (v == null || v.isEmpty()) return OptionalLong.empty();
        return OptionalLong.of(medianOf(v.stream().map(s -> s[1]).toList()));
    }

    /** The kind's median over every measure, so a tool never seen is priced from the kind. */
    private OptionalLong medianOfKind(String step) {
        List<Long> walls = new ArrayList<>();
        for (Map.Entry<String, List<long[]>> e : samples.entrySet()) {
            if (e.getKey().startsWith(step + "\t")) for (long[] s : e.getValue()) walls.add(s[1]);
        }
        return walls.isEmpty() ? OptionalLong.empty() : OptionalLong.of(medianOf(walls));
    }

    /** The median wall per unit, in microseconds, over the samples that counted any. */
    private OptionalLong medianPerUnit(String key) {
        List<long[]> v = samples.get(key);
        if (v == null) return OptionalLong.empty();
        List<Long> rates = new ArrayList<>();
        for (long[] s : v) if (s[0] > 0) rates.add(Math.round(s[1] * 1000.0 / s[0]));
        return rates.isEmpty() ? OptionalLong.empty() : OptionalLong.of(medianOf(rates));
    }

    private static long medianOf(List<Long> v) {
        List<Long> sorted = new ArrayList<>(v);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static Map<String, List<long[]>> read(Path file) {
        return parse(HostStore.section(file, HostStore.EFFORT));
    }

    private static Map<String, List<long[]>> parse(List<String> lines) {
        Map<String, List<long[]>> out = new LinkedHashMap<>();
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length != 4) continue;
            try {
                out.computeIfAbsent(f[0] + "\t" + f[1], k -> new ArrayList<>())
                        .add(new long[] {Long.parseLong(f[2]), Long.parseLong(f[3])});
            } catch (NumberFormatException torn) {
                // a torn line: skipped, the rest still read
            }
        }
        return out;
    }

    private static final Pattern NPM_PACKAGE = Pattern.compile("^\\s*\"node_modules/.*\":\\s*\\{");
    private static final Pattern PNPM_PACKAGE = Pattern.compile("^  \\S.*:$");

    /**
     * The packages the module's node lockfile pins: package-lock's {@code node_modules/} entries,
     * pnpm's or yarn's top-level entries. The lockfile is the module's, or the nearest above it
     * within its repository; 0 when there is none.
     */
    static long lockPackages(Path moduleDir) {
        for (Path d = moduleDir; d != null; d = d.getParent()) {
            try {
                Path npm = d.resolve("package-lock.json");
                if (Files.isRegularFile(npm)) return countLines(npm, NPM_PACKAGE, null);
                Path pnpm = d.resolve("pnpm-lock.yaml");
                if (Files.isRegularFile(pnpm)) return countLines(pnpm, PNPM_PACKAGE, "packages:");
                Path yarn = d.resolve("yarn.lock");
                if (Files.isRegularFile(yarn)) return countYarn(yarn);
            } catch (IOException e) {
                return 0;
            }
            if (Files.exists(d.resolve(".git"))) break;
        }
        return 0;
    }

    private static long countLines(Path file, Pattern entry, @Nullable String section) throws IOException {
        long n = 0;
        boolean in = section == null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                if (section != null && !line.startsWith(" ") && !line.isEmpty()) in = line.equals(section);
                else if (in && entry.matcher(line).find()) n++;
            }
        }
        return n;
    }

    private static long countYarn(Path file) throws IOException {
        long n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isEmpty()
                        && !Character.isWhitespace(line.charAt(0))
                        && line.endsWith(":")
                        && !line.startsWith("#")) n++;
            }
        }
        return n;
    }

    /**
     * The program a {@code package.json} script runs: its last command's ({@code tsc -b && vite
     * build} runs {@code vite}); {@code npm run X} reads as {@code npm-run-X}. Empty when the
     * module has no such script.
     */
    static String scriptTool(Path moduleDir, String script) {
        Path pj = moduleDir.resolve("package.json");
        if (!Files.isRegularFile(pj)) return "";
        try {
            String json = Files.readString(pj, StandardCharsets.UTF_8);
            String scripts = Jsonl.nested(json, "scripts");
            String cmd = scripts == null ? null : Jsonl.str(scripts, script);
            if (cmd == null || cmd.isBlank()) return "";
            String[] parts = cmd.split("&&");
            String[] words = parts[parts.length - 1].trim().split("\\s+");
            if (words.length > 2 && words[0].equals("npm") && words[1].equals("run")) return "npm-run-" + words[2];
            return words.length == 0 ? "" : words[0];
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
