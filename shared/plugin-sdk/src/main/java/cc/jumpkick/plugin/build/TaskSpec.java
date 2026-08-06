// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One plugin task on the module BuildPlan: declared inputs → tool run → declared outputs. Ordering
 * is a <strong>requires</strong> graph (plus engine edges from {@code contributes*}), not fixed
 * lifecycle slots. The engine owns incrementality and skip-on-cache-hit.
 *
 * @see docs/features/build-plan.md
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
    private final List<String> contributesTestClasspath = new ArrayList<>();
    private String transformsClasses;
    private Body body;

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
            if (t != null && !t.isBlank()) requires.add(t);
        }
        return this;
    }

    public TaskSpec inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    public TaskSpec outputs(String... dirs) {
        for (String d : dirs) outputs.add(d);
        return this;
    }

    public TaskSpec contributesClasses(String relDir) {
        contributesClasses.add(relDir);
        return this;
    }

    public TaskSpec contributesResources(String relDir) {
        contributesResources.add(relDir);
        return this;
    }

    public TaskSpec contributesSources(String relDir) {
        contributesSources.add(relDir);
        return this;
    }

    public TaskSpec contributesTestClasspath(String relDir) {
        contributesTestClasspath.add(relDir);
        return this;
    }

    public TaskSpec transformsClasses(String relDir) {
        this.transformsClasses = relDir;
        return this;
    }

    public TaskSpec run(Body body) {
        this.body = body;
        return this;
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

    public List<String> testClasspathContributions() {
        return List.copyOf(contributesTestClasspath);
    }

    public String classesTransform() {
        return transformsClasses;
    }

    public Body body() {
        return body;
    }
}
