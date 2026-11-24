// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Output;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.OutputDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final Path textRoot;
    private @Nullable Facts facts;
    private final Model model;
    private final Text text;
    private final Output output;
    private final @Nullable ReadLedger ledger;

    private GuardRuntime(GuardConfig config) throws IOException {
        this.config = config;
        this.model = config.model() == null ? ModelView.empty() : ModelView.read(config.model());
        this.textRoot = config.textRoot() == null ? config.root() : config.textRoot();
        TextView textView = new TextView(textRoot, config.sources(), config.fixture(), outputDirOf(config));
        this.text = textView;
        this.output = new OutputView(config.poms(), config.jars(), config.coverage());
        Files.createDirectories(config.report().toAbsolutePath().getParent());
        // A fixture run judges a case, not the tree: every guard runs.
        this.ledger = config.fixture() || config.textRoot() != null
                ? null
                : ReadLedger.open(
                        config.report(),
                        ReadLedger.stamp(Classpaths.ofThisJvm(), scope(config)),
                        config.model(),
                        textView);
    }

    /** What the run judges, for the ledger's stamp: the config without the report it is written to. */
    static String scope(GuardConfig config) {
        StringBuilder sb = new StringBuilder();
        for (String line : config.toProperties().split("\n"))
            if (!line.startsWith("report=")) sb.append(line).append('\n');
        return sb.toString();
    }

    /**
     * The root-relative {@code target} or {@code build} directory nearest the report — the output tree
     * it was written into — or {@code null} when the report is outside the root or in neither.
     */
    static @Nullable String outputDirOf(GuardConfig config) {
        Path root = config.root().toAbsolutePath().normalize();
        Path report = config.report().toAbsolutePath().normalize();
        if (!report.startsWith(root)) return null;
        for (Path dir = report.getParent();
                dir != null && dir.startsWith(root) && !dir.equals(root);
                dir = dir.getParent()) {
            String name = String.valueOf(dir.getFileName());
            if (name.equals(OutputDirs.TARGET) || name.equals("build")) {
                return root.relativize(dir).toString().replace('\\', '/');
            }
        }
        return null;
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

    /** The facts view, read on first use: a suite whose guards take none never loads the indexes. */
    public synchronized Facts facts() {
        Facts f = facts;
        if (f == null) {
            try {
                f = new FactsView(merge(config.facts()), merge(config.testFacts()), config.classDirs());
            } catch (IOException e) {
                throw new UncheckedIOException("guard facts: " + e.getMessage(), e);
            }
            facts = f;
        }
        return f;
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

    /** What the guards of the last run read, or {@code null} when every guard runs (a fixture run). */
    @Nullable
    ReadLedger ledger() {
        return ledger;
    }

    public Path reportFile() {
        return config.report();
    }

    /** The workspace root jk configured. */
    public Path root() {
        return config.root();
    }

    /**
     * The directory {@link Text} paths resolve against: the workspace root, or a tree fixture's case
     * when the run judges one as the checkout. A guard that shells out over the tree runs there, so
     * the fixture reaches it like every read does, and reads the files the tool reads through {@link
     * Text} as well: a guard's verdict is replayed while what it read through {@link Text} holds.
     */
    public Path textRoot() {
        return textRoot;
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
