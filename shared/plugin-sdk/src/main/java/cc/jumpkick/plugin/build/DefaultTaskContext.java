// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Harness-supplied implementation of resolve/build/test contribution contexts. Accumulates the
 * implicit task and materializes a {@link TaskSpec} on {@link #run}.
 */
final class DefaultTaskContext implements BuildContext, ResolveContext, TestContext {

    private final BuildPluginContext ctx;
    private String name;
    private final List<String> requires = new ArrayList<>();
    private final List<In> inputs = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private final List<String> contributesSources = new ArrayList<>();
    private final List<String> contributesClasses = new ArrayList<>();
    private final List<String> contributesResources = new ArrayList<>();
    private final List<String> contributesTestClasspath = new ArrayList<>();
    private @Nullable String transformsClasses;
    private @Nullable String stage;
    private boolean bodyRun;

    DefaultTaskContext(BuildPluginContext ctx, String defaultName) {
        this.ctx = ctx;
        this.name = defaultName;
    }

    @Override
    public PluginConfig config() {
        return ctx.config();
    }

    @Override
    public ProjectFacts project() {
        return ctx.project();
    }

    @Override
    public TaskContribution named(String name) {
        this.name = name;
        return this;
    }

    @Override
    public TaskContribution requires(String... taskNames) {
        for (String t : taskNames) {
            if (!t.isBlank()) requires.add(t);
        }
        return this;
    }

    @Override
    public TaskContribution stage(@Nullable String stageWire) {
        this.stage = (stageWire == null || stageWire.isBlank()) ? null : stageWire.trim();
        return this;
    }

    @Override
    public TaskContribution inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    @Override
    public TaskContribution outputs(String... dirs) {
        for (String d : dirs) outputs.add(d);
        return this;
    }

    @Override
    public TaskContribution contributesSources(String relDir) {
        contributesSources.add(relDir);
        return this;
    }

    @Override
    public TaskContribution contributesClasses(String relDir) {
        contributesClasses.add(relDir);
        return this;
    }

    @Override
    public TaskContribution contributesResources(String relDir) {
        contributesResources.add(relDir);
        return this;
    }

    @Override
    public TaskContribution contributesTestClasspath(String relDir) {
        contributesTestClasspath.add(relDir);
        return this;
    }

    @Override
    public TaskContribution transformsClasses(String relDir) {
        this.transformsClasses = relDir;
        return this;
    }

    @Override
    public void run(TaskSpec.Body body) {
        if (bodyRun) {
            throw new IllegalStateException("the implicit task's body is already set for `" + name
                    + "` — register additional tasks with task(TaskSpec)");
        }
        bodyRun = true;
        TaskSpec spec = TaskSpec.named(name).requires(requires.toArray(new String[0]));
        if (stage != null) spec.stage(stage);
        spec.inputs(inputs.toArray(new In[0]));
        spec.outputs(outputs.toArray(new String[0]));
        for (String d : contributesSources) spec.contributesSources(d);
        for (String d : contributesClasses) spec.contributesClasses(d);
        for (String d : contributesResources) spec.contributesResources(d);
        for (String d : contributesTestClasspath) spec.contributesTestClasspath(d);
        if (transformsClasses != null) spec.transformsClasses(transformsClasses);
        spec.run(body);
        ctx.task(spec);
    }

    @Override
    public void task(TaskSpec spec) {
        ctx.task(spec);
    }
}
