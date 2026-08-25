// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link TaskExec} backed by real files under one temp root and nothing else — the step-side
 * sibling of {@link FakePackageIo}, which cannot serve here because {@code PackageIo} does not
 * extend {@code TaskExec}. The android steps with no tool fork are pure functions of what this
 * hands them: config, project facts, the module dir, chained step outputs and a scratch root the
 * declared output dirs resolve under.
 */
final class FakeTaskExec implements TaskExec {

    private final Path root;
    private final Map<String, Object> config = new LinkedHashMap<>();
    private final Map<String, Path> steps = new LinkedHashMap<>();

    /** Every {@link #label} the body emitted, in order — progress is part of the contract. */
    final List<String> labels = new ArrayList<>();

    FakeTaskExec(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(classesDir());
        Files.createDirectories(scratch());
        config.put("namespace", "com.example.app");
        config.put("compile-sdk", 36L);
        config.put("min-sdk", 24L);
    }

    FakeTaskExec config(String key, Object value) {
        config.put(key, value);
        return this;
    }

    /** A step's output root, created on first use — {@code In.stepOutput(name)}'s resolved value. */
    Path step(String name) throws IOException {
        Path dir = steps.get(name);
        if (dir == null) {
            dir = Files.createDirectories(root.resolve("steps").resolve(name));
            steps.put(name, dir);
        }
        return dir;
    }

    @Override
    public Path classesDir() {
        return root.resolve("classes");
    }

    @Override
    public List<Path> runtimeClasspath() {
        return List.of();
    }

    @Override
    public List<PackageIo.RuntimeEntry> runtimeEntries() {
        return List.of();
    }

    @Override
    public PluginConfig config() {
        return new PluginConfig("android", config);
    }

    @Override
    public ProjectFacts project() {
        return new ProjectFacts("com.example", "app", "1.0.0", 25, null, false, false, Map.of());
    }

    @Override
    public Path moduleDir() {
        return root;
    }

    @Override
    public Path scratch() {
        return root.resolve("scratch");
    }

    @Override
    public Optional<Path> extra(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<Path> stepOutput(String step) {
        return Optional.ofNullable(steps.get(step));
    }

    @Override
    public Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    @Override
    public boolean offline() {
        return true;
    }

    @Override
    public void label(String text) {
        labels.add(text);
    }
}
