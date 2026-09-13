// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Output;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The views for this JVM, built once from {@link GuardConfig#PROPERTY}. */
public final class GuardRuntime {

    private static @Nullable GuardRuntime current;
    private static @Nullable String problem;
    private static boolean tried;

    private final GuardConfig config;
    private final Facts facts;
    private final Model model;
    private final Text text;
    private final Output output;

    private GuardRuntime(GuardConfig config) throws IOException {
        this.config = config;
        this.facts = new FactsView(merge(config.facts()), merge(config.testFacts()), config.classDirs());
        this.model = config.model() == null ? ModelView.empty() : ModelView.read(config.model());
        Path textRoot = config.textRoot() == null ? config.root() : config.textRoot();
        this.text = new TextView(textRoot, config.sources(), config.fixture(), outputDirOf(config));
        this.output = new OutputView(config.poms(), config.jars(), config.coverage());
        Files.createDirectories(config.report().toAbsolutePath().getParent());
    }

    /** The root-level directory the report sits under — jk's output tree — or {@code null} when it is elsewhere. */
    static @Nullable String outputDirOf(GuardConfig config) {
        Path root = config.root().toAbsolutePath().normalize();
        Path report = config.report().toAbsolutePath().normalize();
        if (!report.startsWith(root)) return null;
        Path rel = root.relativize(report);
        return rel.getNameCount() > 1 ? rel.getName(0).toString() : null;
    }

    /** The runtime for this JVM, or {@code null} when jk did not configure one. */
    public static synchronized @Nullable GuardRuntime current() {
        if (!tried) {
            tried = true;
            String file = System.getProperty(GuardConfig.PROPERTY);
            if (file != null && !file.isBlank()) {
                try {
                    current = new GuardRuntime(GuardConfig.read(Path.of(file)));
                } catch (IOException | RuntimeException e) {
                    problem = e.getMessage();
                }
            }
        }
        return current;
    }

    /** For tests: install a runtime from a config directly. */
    public static synchronized void install(GuardConfig config) throws IOException {
        current = new GuardRuntime(config);
        tried = true;
        problem = null;
    }

    public static synchronized @Nullable String problem() {
        return problem;
    }

    public Facts facts() {
        return facts;
    }

    public Model model() {
        return model;
    }

    public Text text() {
        return text;
    }

    public Output output() {
        return output;
    }

    public Path reportFile() {
        return config.report();
    }

    /** The workspace root jk configured. */
    public Path root() {
        return config.root();
    }

    static FactsIndex merge(List<Path> files) throws IOException {
        if (files.isEmpty()) return FactsIndex.EMPTY;
        if (files.size() == 1)
            return Files.isRegularFile(files.get(0)) ? FactsFormat.read(files.get(0)) : FactsIndex.EMPTY;
        Map<String, ClassFacts> all = new LinkedHashMap<>();
        StringBuilder digest = new StringBuilder();
        for (Path f : files) {
            if (!Files.isRegularFile(f)) continue;
            FactsIndex one = FactsFormat.read(f);
            all.putAll(one.classes());
            digest.append(one.bodyDigest()).append(';');
        }
        return new FactsIndex(all, Map.of(), digest.toString());
    }
}
