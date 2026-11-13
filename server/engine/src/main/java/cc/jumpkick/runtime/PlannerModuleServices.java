// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.W_RESOURCES;
import static cc.jumpkick.runtime.BuildPlanner.mainClasses;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.ModuleDescriptors;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.MirroredOutputs;
import java.io.IOException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@code module-services} step: {@code [build] module-services = true} writes one {@code
 * META-INF/services/<service>} file into the main classes tree for every {@code provides} the
 * compiled {@code module-info} declares. On the module path {@code ServiceLoader} reads the
 * descriptor itself, but a class path, an annotation processor path and every tool that scans
 * {@code META-INF/services} see only the files. A library that declares its services once, in
 * {@code module-info.java}, is then found either way, which is what Helidon's own services plugin
 * does for its Maven build.
 *
 * <p>The step runs after the resource copy, so the module's own resources are already in the tree.
 * A file there of the same name that names the same providers is left alone; one that names others
 * fails the step, naming both lists, rather than letting either silently win. A ledger records
 * what the step wrote, so a {@code provides} removed from the descriptor removes the file it wrote
 * and never one the resources own. The ledger is a merge ledger, so a compile's action record
 * neither stores the files nor lays them back down on a restore: they are this step's outputs.
 */
final class PlannerModuleServices {

    /** Where the files live inside the classes tree. */
    static final String SERVICES_DIR = "META-INF/services/";

    /** The ledger's name under {@code target/incremental/}: what the step last wrote. */
    static final String LEDGER = MirroredOutputs.mergedLedger(TaskNames.MODULE_SERVICES);

    private static final String DESCRIPTOR = "module-info.class";

    private PlannerModuleServices() {}

    static Task step(BuildPlanner.Ctx cx) {
        return Task.builder(TaskNames.MODULE_SERVICES)
                .forecast(StepForecast.priced(s -> ForecastScope.of(s).moduleServicesDrift
                        ? new TaskForecast.Task(
                                TaskNames.MODULE_SERVICES, TaskForecast.Status.RUN, "provides changed", null)
                        : null))
                .stage(BuildLogicAnchor.AFTER_RESOURCES.stage())
                .label("Module services")
                .kind(TaskKind.IO)
                // After the compile that writes module-info.class, and the resource copy ahead of it.
                .requires(PlannerResources.CLASSES_WHOLE)
                .weight(W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = mainClasses(ctx);
                    Path incremental = incremental(ctx.require(LAYOUT));
                    Plan plan = plan(classes, incremental);
                    int changed = apply(plan, classes, incremental);
                    ctx.label(
                            changed > 0
                                    ? "write " + changed + " services file(s)"
                                    : plan.owned().size() + " services file(s) up-to-date");
                    if (changed == 0) ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    /** The module's {@code target/incremental/}, where the step's ledger and the resource ledger live. */
    static Path incremental(BuildLayout layout) {
        return layout.buildDir().resolve("incremental");
    }

    /**
     * What the step would do to {@code classes}: the files to write (by path inside the tree, only
     * those whose bytes differ), the files it wrote before and no longer owns, and the set it owns
     * afterwards.
     */
    record Plan(Map<String, byte[]> writes, Set<String> removals, Set<String> owned) {

        boolean changes() {
            return !writes.isEmpty() || !removals.isEmpty();
        }
    }

    /**
     * Plan the step against the tree as it stands. Throws when a file the step does not own (a
     * resource, a processor's output) names other providers than the descriptor does, before
     * anything is written.
     */
    static Plan plan(Path classes, Path incremental) throws IOException {
        Set<String> previous = readLedger(incremental.resolve(LEDGER));
        Set<String> resources = readLedger(incremental.resolve(ResourceMirror.LEDGER));
        Map<String, byte[]> writes = new TreeMap<>();
        Set<String> owned = new TreeSet<>();
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : provides(classes).entrySet()) {
            String rel = SERVICES_DIR + e.getKey();
            byte[] bytes = render(e.getValue());
            Path target = classes.resolve(rel);
            boolean exists = Files.isRegularFile(target);
            if (exists && (resources.contains(rel) || !previous.contains(rel))) {
                // Another writer's file: the same providers are fine, others are a conflict.
                List<String> theirs = providers(Files.readAllBytes(target));
                if (!new LinkedHashSet<>(theirs).equals(new LinkedHashSet<>(e.getValue()))) {
                    conflicts.add(conflict(rel, theirs, e.getValue()));
                }
                continue;
            }
            owned.add(rel);
            if (!exists || !Arrays.equals(Files.readAllBytes(target), bytes)) writes.put(rel, bytes);
        }
        if (!conflicts.isEmpty()) throw new IOException(String.join("\n", conflicts));
        Set<String> removals = new TreeSet<>();
        for (String rel : previous) {
            if (!owned.contains(rel) && !resources.contains(rel) && Files.exists(classes.resolve(rel))) {
                removals.add(rel);
            }
        }
        return new Plan(writes, removals, owned);
    }

    /**
     * Carry {@code plan} out and rewrite the ledger. Returns how many files were written or
     * removed; zero is an up-to-date tree.
     */
    static int apply(Plan plan, Path classes, Path incremental) throws IOException {
        for (Map.Entry<String, byte[]> e : plan.writes().entrySet()) {
            Path target = classes.resolve(e.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
        for (String rel : plan.removals()) Files.deleteIfExists(classes.resolve(rel));
        if (!plan.removals().isEmpty()) pruneEmpty(classes);
        Files.createDirectories(incremental);
        Set<String> owned = plan.owned();
        AtomicWrites.replaceIfChanged(
                incremental.resolve(LEDGER), owned.isEmpty() ? "" : String.join("\n", owned) + "\n");
        return plan.writes().size() + plan.removals().size();
    }

    /**
     * True when the step would change the tree, or fail on it: the forecast's drift, so {@code jk
     * explain} names the step and the jar keyed on the tree it changes.
     */
    static boolean outOfSync(Path classes, Path incremental) {
        try {
            return plan(classes, incremental).changes();
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * The compiled descriptor's {@code provides}, by service, each provider list in declaration
     * order. Empty when the tree holds no {@code module-info.class}.
     */
    static Map<String, List<String>> provides(Path classes) throws IOException {
        Path descriptor = classes.resolve(DESCRIPTOR);
        if (!Files.isRegularFile(descriptor)) return Map.of();
        ModuleDescriptor module;
        try {
            module = ModuleDescriptors.read(Files.readAllBytes(descriptor));
        } catch (InvalidModuleDescriptorException e) {
            throw new IOException("module-services: cannot read " + descriptor + ": " + e.getMessage(), e);
        }
        Map<String, List<String>> out = new TreeMap<>();
        for (ModuleDescriptor.Provides p : module.provides()) out.put(p.service(), p.providers());
        return out;
    }

    /** A services file: one provider per line, UTF-8, each line ending in a newline. */
    static byte[] render(List<String> providers) {
        StringBuilder sb = new StringBuilder();
        for (String provider : providers) sb.append(provider).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The providers a services file names, as {@code ServiceLoader} reads it: comments and blanks dropped. */
    static List<String> providers(byte[] file) {
        List<String> out = new ArrayList<>();
        for (String line : new String(file, StandardCharsets.UTF_8).split("\\R")) {
            int hash = line.indexOf('#');
            String name = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private static String conflict(String rel, List<String> theirs, List<String> declared) {
        return "module-services: " + rel + " in the classes tree names " + names(theirs)
                + " but module-info provides " + names(declared)
                + "; make the resource match module-info.java or remove it";
    }

    private static String names(List<String> providers) {
        return providers.isEmpty() ? "no provider" : "[" + String.join(", ", providers) + "]";
    }

    private static Set<String> readLedger(Path ledger) throws IOException {
        if (!Files.isRegularFile(ledger)) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) out.add(line.strip());
        }
        return out;
    }

    /** Remove {@code META-INF/services} and then {@code META-INF} when the removals left them empty. */
    private static void pruneEmpty(Path classes) throws IOException {
        for (String dir : List.of(SERVICES_DIR, "META-INF/")) {
            Path d = classes.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try {
                Files.delete(d);
            } catch (DirectoryNotEmptyException e) {
                return;
            }
        }
    }
}
