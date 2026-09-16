// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One generator to run: what a {@code [generate.<name>]} entry says, or what a preset table
 * ({@code [openapi]}) expands to. {@link #task()} is its build-plan declaration; the body is
 * {@link GeneratorStep}.
 *
 * @param name the entry name; the step is {@code generate-<name>}
 * @param toolArtifact the step-dependency artifact the engine hands the body (the tool's jar, or
 *     the directory holding its runtime closure)
 * @param toolCoordinate the tool's {@code group:artifact[:version]}, which names the jar whose
 *     {@code Main-Class} runs when {@code main} is null
 * @param main the class to run, or null for the tool jar's {@code Main-Class}
 * @param inputs module-relative files or globs the tool reads
 * @param args the tool's arguments, before {@link Arguments} expansion
 * @param contributes where the output joins the module
 * @param out the output directory's name under the step's scratch
 */
public record GeneratorEntry(
        String name,
        String toolArtifact,
        String toolCoordinate,
        @Nullable String main,
        List<String> inputs,
        List<String> args,
        Contribution contributes,
        String out) {

    /** Where a generator's output joins the module. */
    public enum Contribution {
        SOURCES,
        RESOURCES;

        static Contribution parse(String raw, String where) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "sources" -> SOURCES;
                case "resources" -> RESOURCES;
                case "test-sources" ->
                    throw new IllegalArgumentException(where + ": contributes = \"test-sources\" is not available"
                            + " yet — a generator feeds the main source set (sources) or the resources");
                default ->
                    throw new IllegalArgumentException(
                            where + ": contributes must be sources or resources, not \"" + raw + "\"");
            };
        }
    }

    public GeneratorEntry {
        Objects.requireNonNull(name, "name");
        inputs = List.copyOf(inputs);
        args = List.copyOf(args);
        if (inputs.isEmpty()) throw new IllegalArgumentException("[generate." + name + "] declares no inputs");
    }

    /** The entry as its validated {@code [generate.<name>]} table reads; the tool artifact is the name. */
    @SuppressWarnings("unchecked")
    public static GeneratorEntry fromConfig(String name, Map<String, Object> values) {
        String where = "[generate." + name + "]";
        String tool = (String) Objects.requireNonNull(values.get("tool"), where + " tool");
        return new GeneratorEntry(
                name,
                name,
                tool,
                (String) values.get("main"),
                (List<String>) values.getOrDefault("inputs", List.of()),
                (List<String>) values.getOrDefault("args", List.of()),
                Contribution.parse((String) values.getOrDefault("contributes", "sources"), where),
                (String) values.getOrDefault("out", "generated/" + name));
    }

    /** The step's name on the plan: {@code generate-<name>}, the name a manifest's {@code for-step} uses. */
    public String stepName() {
        return "generate-" + name;
    }

    /**
     * The declared task: the inputs' glob bases and the entry's config are the cache key, the
     * output dir is contributed to the module. The engine fingerprints a glob's base (the whole
     * directory when the pattern has one), a superset of what the glob matches.
     */
    public TaskSpec task() {
        List<In> ins = new ArrayList<>();
        for (String base : inputBases()) ins.add(In.projectFiles(base));
        ins.add(In.config());
        TaskSpec spec = TaskSpec.named(stepName())
                .stage("generate")
                .inputs(ins.toArray(In[]::new))
                .outputs(out)
                .run(exec -> GeneratorStep.run(exec, this));
        return switch (contributes) {
            case SOURCES -> spec.contributesSources(out);
            case RESOURCES -> spec.contributesResources(out);
        };
    }

    /** Each input's path up to its first glob segment, deduplicated in declaration order. */
    List<String> inputBases() {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        for (String input : inputs) bases.add(Inputs.globBase(input));
        return List.copyOf(bases);
    }
}
