// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link PackageIo} backed by real files under one temp dir and nothing else — no engine, no
 * network, no Android SDK. The android packagers are pure functions of what this hands them, so
 * they can be driven to completion against it and asserted by reading the archive they wrote.
 *
 * <p>The shape mirrors what the engine really passes: the artifact lands under {@code target/lib/}
 * ({@code BuildLayout.artifactDir} for a module with no declared {@code main} — every Android app
 * and library), and its parent directory already exists, because {@code PlannerPackage} creates it
 * before forking the worker. A packager that relied on creating it itself would still pass here;
 * one that relied on the engine having created it would fail if this fake did not.
 */
final class FakePackageIo implements PackageIo {

    private final Path root;
    private final Map<String, Object> config = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();
    private final Map<String, Path> extras = new LinkedHashMap<>();
    private final Map<String, Path> steps = new LinkedHashMap<>();
    private final List<RuntimeEntry> entries = new ArrayList<>();

    /** Every {@link #label} the body emitted, in order — progress is part of the contract. */
    final List<String> labels = new ArrayList<>();

    private Path artifact;

    FakePackageIo(Path root, String artifactName) throws IOException {
        this.root = root;
        this.artifact = Files.createDirectories(root.resolve("target/lib")).resolve(artifactName);
        Files.createDirectories(classesDir());
        config.put("namespace", "com.example.app");
        config.put("compile-sdk", 36L);
        config.put("min-sdk", 24L);
    }

    FakePackageIo config(String key, Object value) {
        config.put(key, value);
        return this;
    }

    FakePackageIo secret(String key, String value) {
        secrets.put(key, value);
        return this;
    }

    FakePackageIo extra(String name, Path path) {
        extras.put(name, path);
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

    /**
     * An exploded AAR dependency appended to the runtime entries. Order is classpath order, which
     * is the merge order for resources, manifests, assets and native libs.
     */
    Path aar(String fileName) throws IOException {
        Path container = Files.createDirectories(root.resolve("deps").resolve(fileName + "-container"));
        entries.add(new RuntimeEntry(fileName, null, false, container, "com.example", fileName, "1.0"));
        return container;
    }

    /** A plain jar dependency: a runtime entry with no exploded container, so not an AAR. */
    Path jar(String fileName) throws IOException {
        Path file = write(root.resolve("deps").resolve(fileName), "not-really-a-jar");
        entries.add(new RuntimeEntry(fileName, file, false, null, "com.example", fileName, "1.0"));
        return file;
    }

    /** Write {@code text} at {@code file}, creating parents — the fixtures' one spelling. */
    static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Override
    public Path classesDir() {
        return root.resolve("classes");
    }

    @Override
    public Path moduleDir() {
        return root;
    }

    @Override
    public List<RuntimeEntry> runtimeEntries() {
        return List.copyOf(entries);
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
    public Optional<Path> stepOutput(String step) {
        return Optional.ofNullable(steps.get(step));
    }

    @Override
    public Optional<Path> extra(String name) {
        return Optional.ofNullable(extras.get(name));
    }

    @Override
    public Optional<String> secret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    @Override
    public Path artifactPath() {
        return artifact;
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
