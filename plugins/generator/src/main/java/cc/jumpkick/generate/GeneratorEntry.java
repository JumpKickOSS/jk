// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One generator to run: what a {@code [generate.<name>]} entry says, or what a preset table
 * ({@code [openapi]}, {@code [localizer]}) expands to. {@link #task()} is its build-plan
 * declaration; the body is {@link GeneratorStep}.
 *
 * @param name the entry name; the step is {@code generate-<name>}
 * @param toolArtifact the step-dependency artifact the engine hands the body (the tool's jar, or
 *     the directory holding its runtime closure)
 * @param toolCoordinate the tool's {@code group:artifact[:version]}, which names the jar whose
 *     {@code Main-Class} runs when {@code main} is null
 * @param main the class to run, or null for the tool jar's {@code Main-Class}
 * @param inputs module-relative files or globs the tool reads; may be empty when {@code unpack}
 *     names what it reads
 * @param unpack a jar coordinate whose contents are extracted for the tool ({@code ${unpacked}}),
 *     or null
 * @param args the tool's arguments, before {@link Arguments} expansion
 * @param contributes where the output joins the module
 * @param out the output directory's name under the step's scratch
 * @param classpath entries put ahead of the tool's closure on the forked classpath — a preset's
 *     own {@code main} over a library that ships none
 * @param discard paths under the output, or globs over it, removed once the tool has run: what it
 *     writes beside its contribution (DGS codegen's {@code generated-examples})
 */
public record GeneratorEntry(
        String name,
        String toolArtifact,
        String toolCoordinate,
        @Nullable String main,
        List<String> inputs,
        @Nullable String unpack,
        List<String> args,
        Contribution contributes,
        String out,
        List<Path> classpath,
        List<String> discard) {

    /** Where a generator's output joins the module. */
    public enum Contribution {
        SOURCES,
        TEST_SOURCES,
        RESOURCES;

        static Contribution parse(String raw, String where) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "sources" -> SOURCES;
                case "test-sources" -> TEST_SOURCES;
                case "resources" -> RESOURCES;
                default ->
                    throw new IllegalArgumentException(
                            where + ": contributes must be sources, test-sources or resources, not \"" + raw + "\"");
            };
        }
    }

    public GeneratorEntry {
        Objects.requireNonNull(name, "name");
        inputs = List.copyOf(inputs);
        args = List.copyOf(args);
        classpath = List.copyOf(classpath);
        discard = List.copyOf(discard);
        if (inputs.isEmpty() && unpack == null) {
            throw new IllegalArgumentException("[generate." + name + "] declares no inputs — name the files the tool"
                    + " reads (inputs), or the jar whose contents it reads (unpack)");
        }
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
                (String) values.get("unpack"),
                (List<String>) values.getOrDefault("args", List.of()),
                Contribution.parse((String) values.getOrDefault("contributes", "sources"), where),
                (String) values.getOrDefault("out", "generated/" + name),
                List.of(),
                (List<String>) values.getOrDefault("discard", List.of()));
    }

    /** The step's name on the plan: {@code generate-<name>}, the name a manifest's {@code for-step} uses. */
    public String stepName() {
        return "generate-" + name;
    }

    /** The step-dependency artifact carrying the jar {@code unpack} names: {@code <name>-unpack}. */
    public String unpackArtifact() {
        return name + "-unpack";
    }

    /**
     * The declared task: the inputs' glob bases and the entry's config are the cache key, the
     * output dir is contributed to the module. The engine fingerprints a glob's base (the whole
     * directory when the pattern has one), a superset of what the glob matches; a jar to unpack
     * rides in the key as the step-dependency it is.
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
            case TEST_SOURCES -> spec.contributesTestSources(out);
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
