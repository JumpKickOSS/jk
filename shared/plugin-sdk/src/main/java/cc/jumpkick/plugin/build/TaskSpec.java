// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.host.PathUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One plugin task on the module BuildPlan: declared inputs → tool run → declared outputs. Ordering
 * is a <strong>requires</strong> graph (plus engine edges from {@code contributes*}), not fixed
 * lifecycle slots. The engine owns incrementality and skip-on-cache-hit.
 *
 */
public final class TaskSpec {

    /** Task body — runs in the plugin's worker JVM, never in the engine. */
    @FunctionalInterface
    public interface Body {
        void run(TaskExec exec) throws Exception;
    }

    private final String name;
    private final List<String> requires = new ArrayList<>();
    private final List<In> inputs = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private final List<String> contributesClasses = new ArrayList<>();
    private final List<String> contributesResources = new ArrayList<>();
    private final List<String> contributesSources = new ArrayList<>();
    private final List<String> contributesTestSources = new ArrayList<>();
    private final List<String> contributesTestClasspath = new ArrayList<>();
    private final List<String> contributesTestJvmArgs = new ArrayList<>();
    private boolean oneTestJvm;
    private @Nullable KeyProbe keyProbe;
    private @Nullable String transformsClasses;
    /**
     * Optional product stage wire name ({@code generate}, {@code compile}, {@code test}, …). Null
     * means the engine infers from task name / contributions.
     */
    private @Nullable String stage;

    private @Nullable Body body;

    private TaskSpec(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static TaskSpec named(String name) {
        return new TaskSpec(name);
    }

    /**
     * Upstream tasks that must succeed before this one runs. Names are engine task ids or
     * {@code plugin-&lt;name&gt;} for peer plugin tasks.
     */
    public TaskSpec requires(String... taskNames) {
        for (String t : taskNames) {
            if (!t.isBlank()) requires.add(t);
        }
        return this;
    }

    public TaskSpec inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    public TaskSpec outputs(String... dirs) {
        for (String d : dirs) outputs.add(requireBelowScratch(name, "outputs", d));
        return this;
    }

    public TaskSpec contributesClasses(String relDir) {
        contributesClasses.add(requireBelowScratch(name, "contributesClasses", relDir));
        return this;
    }

    public TaskSpec contributesResources(String relDir) {
        contributesResources.add(requireBelowScratch(name, "contributesResources", relDir));
        return this;
    }

    public TaskSpec contributesSources(String relDir) {
        contributesSources.add(requireBelowScratch(name, "contributesSources", relDir));
        return this;
    }

    /** See {@link TaskContribution#contributesTestSources}. */
    public TaskSpec contributesTestSources(String relDir) {
        contributesTestSources.add(requireBelowScratch(name, "contributesTestSources", relDir));
        return this;
    }

    /** See {@link TaskContribution#contributesTestClasspath}. */
    public TaskSpec contributesTestClasspath(String relPath) {
        contributesTestClasspath.add(requireBelowScratch(name, "contributesTestClasspath", relPath));
        return this;
    }

    /** See {@link TaskContribution#contributesTestJvmArgs}. */
    public TaskSpec contributesTestJvmArgs(String relFile) {
        contributesTestJvmArgs.add(requireBelowScratch(name, "contributesTestJvmArgs", relFile));
        return this;
    }

    /** See {@link TaskContribution#oneTestJvm}. */
    public TaskSpec oneTestJvm() {
        this.oneTestJvm = true;
        return this;
    }

    /** An input measured when the step's key is computed; see {@link KeyProbe}. */
    public TaskSpec keyProbe(KeyProbe probe) {
        this.keyProbe = probe;
        return this;
    }

    public TaskSpec transformsClasses(String relDir) {
        this.transformsClasses = requireBelowScratch(name, "transformsClasses", relDir);
        return this;
    }

    /**
     * Product stage for UI fold / ETA ({@code generate}, {@code compile}, {@code test},
     * {@code package}, …). Matches {@code BuildStage#wireName()}. Omit to let the engine infer.
     */
    public TaskSpec stage(@Nullable String stageWire) {
        this.stage = (stageWire == null || stageWire.isBlank()) ? null : stageWire.trim();
        return this;
    }

    public TaskSpec run(Body body) {
        this.body = body;
        return this;
    }

    /**
     * {@code rel}, when it names something strictly below the task's scratch root, where every
     * declared output and contribution resolves; one that is absolute, names the root itself or
     * climbs out with {@code ..} is refused. The engine applies the same rule to a describe reply.
     */
    public static String requireBelowScratch(String task, String field, String rel) {
        if (!PathUtil.isStrictlyBelow(rel)) {
            throw new IllegalArgumentException(
                    "plugin task " + task + ": " + field + "(\"" + rel + "\") is not under the task's output root");
        }
        return rel;
    }

    public String name() {
        return name;
    }

    public List<String> requires() {
        return List.copyOf(requires);
    }

    public List<In> declaredInputs() {
        return List.copyOf(inputs);
    }

    public List<String> declaredOutputs() {
        return List.copyOf(outputs);
    }

    public List<String> classesContributions() {
        return List.copyOf(contributesClasses);
    }

    public List<String> resourcesContributions() {
        return List.copyOf(contributesResources);
    }

    public List<String> sourcesContributions() {
        return List.copyOf(contributesSources);
    }

    public List<String> testSourcesContributions() {
        return List.copyOf(contributesTestSources);
    }

    public List<String> testClasspathContributions() {
        return List.copyOf(contributesTestClasspath);
    }

    public List<String> testJvmArgsContributions() {
        return List.copyOf(contributesTestJvmArgs);
    }

    /** Whether the module's tests run in one JVM ({@link #oneTestJvm()}). */
    public boolean runsTestsInOneJvm() {
        return oneTestJvm;
    }

    public @Nullable KeyProbe keyProbe() {
        return keyProbe;
    }

    public @Nullable String classesTransform() {
        return transformsClasses;
    }

    /** Optional stage wire name, or null when unset. */
    public @Nullable String stage() {
        return stage;
    }

    public @Nullable Body body() {
        return body;
    }
}
