// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The run's inputs as the engine wrote them ({@code -Djk.guard.config=<file>}, a properties file).
 * Lists are {@code |}-separated paths.
 *
 * @param report where guards append their lines
 * @param root the workspace root
 * @param module the module under test, {@code ""} for the root
 * @param facts main facts indexes in scope (one for a MODULE suite, every module's for WORKSPACE)
 * @param testFacts test facts indexes in scope, possibly empty
 * @param model the model snapshot (JSON), or {@code null}
 * @param sources source roots {@link cc.jumpkick.guard.api.Text} may read
 * @param classDirs class directories the indexes were built from
 * @param poms POM files the build produced for the scope
 * @param jars jars the build produced
 * @param coverage the coverage report, or {@code null}
 * @param fixture whether this run judges a fixture rather than the tree
 */
public record GuardConfig(
        Path report,
        Path root,
        String module,
        List<Path> facts,
        List<Path> testFacts,
        @Nullable Path model,
        List<Path> sources,
        List<Path> classDirs,
        List<Path> poms,
        List<Path> jars,
        @Nullable Path coverage,
        /** A {@code jk guard test} run over a fixture: {@code Text.files} matches the fixture's files by name. */
        boolean fixture) {

    public static final String PROPERTY = "jk.guard.config";

    public static GuardConfig read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new GuardConfig(
                Path.of(require(p, "report")),
                Path.of(require(p, "root")),
                p.getProperty("module", ""),
                paths(p.getProperty("facts", "")),
                paths(p.getProperty("test-facts", "")),
                optional(p.getProperty("model")),
                paths(p.getProperty("sources", "")),
                paths(p.getProperty("class-dirs", "")),
                paths(p.getProperty("poms", "")),
                paths(p.getProperty("jars", "")),
                optional(p.getProperty("coverage")),
                Boolean.parseBoolean(p.getProperty("fixture", "false")));
    }

    /** The properties text for these values; the engine writes it, {@link #read} reads it back. */
    public String toProperties() {
        StringBuilder sb = new StringBuilder();
        sb.append("report=").append(escape(report)).append('\n');
        sb.append("root=").append(escape(root)).append('\n');
        sb.append("module=").append(module).append('\n');
        sb.append("facts=").append(join(facts)).append('\n');
        sb.append("test-facts=").append(join(testFacts)).append('\n');
        if (model != null) sb.append("model=").append(escape(model)).append('\n');
        sb.append("sources=").append(join(sources)).append('\n');
        sb.append("class-dirs=").append(join(classDirs)).append('\n');
        sb.append("poms=").append(join(poms)).append('\n');
        sb.append("jars=").append(join(jars)).append('\n');
        if (coverage != null) sb.append("coverage=").append(escape(coverage)).append('\n');
        sb.append("fixture=").append(fixture).append('\n');
        return sb.toString();
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("jk.guard.config lacks `" + key + "`");
        return v;
    }

    private static @Nullable Path optional(@Nullable String v) {
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    private static List<Path> paths(String joined) {
        List<Path> out = new ArrayList<>();
        for (String s : joined.split("\\|")) if (!s.isBlank()) out.add(Path.of(s));
        return List.copyOf(out);
    }

    private static String join(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (sb.length() > 0) sb.append('|');
            sb.append(escape(p));
        }
        return sb.toString();
    }

    private static String escape(Path p) {
        return p.toString().replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=");
    }
}
