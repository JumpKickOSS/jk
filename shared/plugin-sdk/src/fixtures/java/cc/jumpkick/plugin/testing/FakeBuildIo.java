// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.testing;

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
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The engine's side of a build plugin, faked over one temp directory: real files, no engine, no
 * network, no tool forks.
 *
 * <p>It implements <strong>both</strong> halves of the SPI a plugin body can be handed —
 * {@link PackageIo} for a packager and {@link TaskExec} for a step — because the two interfaces
 * overlap on nine of their methods and neither extends the other, so a module that tests both ends
 * up writing the same fake twice. {@code plugins/android} did exactly that.
 *
 * <p>Five fakes existed before this one: {@code android}'s {@code FakePackageIo} and
 * {@code FakeTaskExec}, and a nested {@code FakeIo} inside each of {@code spring-boot}'s,
 * {@code grails}'s and {@code quarkus}'s packager tests. They agreed on everything that matters and
 * differed only in fixture data — which is the definition of the wrong place for the code. The
 * consequence was structural, not cosmetic: adding a method to {@code PackageIo} broke four
 * modules' tests, so the SPI was harder to change than it should be.
 *
 * <p>What it deliberately does <em>not</em> do is impersonate a third party. It stands in for the
 * jk engine — our own shape — so sharing it cannot let a broken writer feed its own broken reader.
 * A plugin's fixture <em>content</em> (an AAR's exploded layout, a boot loader jar, a Quarkus
 * augment tree) stays in the plugin's own test, built with {@link #jar} or by hand.
 *
 * <p>Layout, mirroring what the engine really passes: the artifact lands under
 * {@code <root>/target/lib/} — {@code BuildLayout.artifactDir} for a module with no declared
 * {@code main}, which is every Android app and library — and its parent directory already exists,
 * because {@code PlannerPackage} creates it before forking the worker. A packager that created the
 * directory itself would pass either way; one that relied on the engine having created it would
 * fail against a fake that did not.
 */
public final class FakeBuildIo implements PackageIo, TaskExec {

    private final Path root;
    private final String pluginId;
    private final Map<String, Object> config = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();
    private final Map<String, Path> extras = new LinkedHashMap<>();
    private final Map<String, Path> steps = new LinkedHashMap<>();
    private final List<RuntimeEntry> entries = new ArrayList<>();

    /** Jars on the compile classpath only — the {@code provided} scope's view. */
    private final List<Path> compileOnly = new ArrayList<>();

    /** Every {@link #label} the body emitted, in order — progress is part of the contract. */
    private final List<String> labels = new ArrayList<>();

    /** Every {@link #diagnostic} the body reported, rendered {@code severity: file:line:col: message}. */
    private final List<String> diagnostics = new ArrayList<>();

    /** Every {@link #produced} path the body declared — the packaging cache stores these. */
    private final List<Path> produced = new ArrayList<>();

    private Path classesDir;
    private Path moduleDir;
    private Path artifact;
    private ProjectFacts project = new ProjectFacts("com.example", "app", "1.0.0", 25, null, false, false, Map.of());
    private boolean offline = true;

    /**
     * @param root a {@code @TempDir}: everything this fake hands out lives under it
     * @param pluginId the {@code [<id>]} table name the body will read its config from
     */
    public FakeBuildIo(Path root, String pluginId) throws IOException {
        this.root = root;
        this.pluginId = pluginId;
        this.classesDir = root.resolve("classes");
        this.moduleDir = root;
        this.artifact = Files.createDirectories(root.resolve("target/lib")).resolve("app-1.0.0.jar");
        Files.createDirectories(classesDir);
        Files.createDirectories(scratch());
    }

    // ---- fixture setup -------------------------------------------------------------------------

    /** Set one key of the plugin's config table. */
    public FakeBuildIo config(String key, Object value) {
        config.put(key, value);
        return this;
    }

    /** Set the whole plugin config table at once. */
    public FakeBuildIo config(Map<String, Object> values) {
        config.putAll(values);
        return this;
    }

    /** Set a resolved secret ({@code env:}-indirected signing credentials). */
    public FakeBuildIo secret(String key, String value) {
        secrets.put(key, value);
        return this;
    }

    /** Register an engine-supplied extra (a manifest-contributed step/packager dependency). */
    public FakeBuildIo extra(String name, Path path) {
        extras.put(name, path);
        return this;
    }

    /** Replace the project facts wholesale. */
    public FakeBuildIo project(ProjectFacts facts) {
        this.project = facts;
        return this;
    }

    /**
     * The four project facts a packager actually reads. Keeps the defaults for the capability flags
     * and the manifest table; use {@link #project(ProjectFacts)} when those matter.
     */
    public FakeBuildIo project(String group, String name, String version, @Nullable String mainClass) {
        this.project = new ProjectFacts(group, name, version, 25, mainClass, false, false, Map.of());
        return this;
    }

    /** Whether this job forbids network access. Defaults to true: a unit test has no network. */
    public FakeBuildIo offline(boolean value) {
        this.offline = value;
        return this;
    }

    /** Point {@link #classesDir()} somewhere other than {@code <root>/classes}. */
    public FakeBuildIo classesDir(Path dir) {
        this.classesDir = dir;
        return this;
    }

    /** Point {@link #moduleDir()} somewhere other than {@code root}. */
    public FakeBuildIo moduleDir(Path dir) {
        this.moduleDir = dir;
        return this;
    }

    /** The produced artifact's name under {@code <root>/target/lib/}. */
    public FakeBuildIo artifact(String fileName) throws IOException {
        this.artifact = Files.createDirectories(root.resolve("target/lib")).resolve(fileName);
        return this;
    }

    /** The produced artifact's full path, for a test that asserts on a layout of its own. */
    public FakeBuildIo artifactPath(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.artifact = path;
        return this;
    }

    /** A chained step's output root, created on first use — {@code In.stepOutput(name)}'s value. */
    public Path step(String name) throws IOException {
        @Nullable Path dir = steps.get(name);
        if (dir == null) {
            dir = Files.createDirectories(root.resolve("steps").resolve(name));
            steps.put(name, dir);
        }
        return dir;
    }

    /** A chained step's output root at a path the test already built. */
    public FakeBuildIo step(String name, Path dir) {
        steps.put(name, dir);
        return this;
    }

    /** Append a runtime entry verbatim, for a shape the helpers below do not cover. */
    public FakeBuildIo entry(RuntimeEntry entry) {
        entries.add(entry);
        return this;
    }

    /**
     * Append a coordinate-named runtime entry backed by a real one-class jar, and return its path.
     * Order is classpath order, which is merge order for resources, manifests, assets and libs.
     */
    public Path entry(String fileName, String group, String artifactId, String version, String classEntry)
            throws IOException {
        Path jar = jar(fileName, classEntry);
        entries.add(new RuntimeEntry(fileName, jar, false, null, group, artifactId, version));
        return jar;
    }

    /**
     * As {@link #entry(String, String, String, String, String)}, deriving the jar's single class
     * entry from the artifact id ({@code spring-boot} to {@code spring/boot.class}) — enough
     * whenever the test only needs the coordinate to be findable on the closure.
     */
    public Path entry(String fileName, String group, String artifactId, String version) throws IOException {
        return entry(fileName, group, artifactId, version, artifactId.replace('-', '/') + ".class");
    }

    /**
     * Append an exploded-container runtime entry (an Android AAR: {@code classes.jar}, {@code res/},
     * {@code AndroidManifest.xml}, {@code R.txt} inside) and return the container directory.
     */
    public Path container(String fileName) throws IOException {
        Path container = Files.createDirectories(root.resolve("deps").resolve(fileName + "-container"));
        entries.add(new RuntimeEntry(fileName, null, false, container, "com.example", fileName, "1.0"));
        return container;
    }

    /** Append a plain-file runtime entry with no exploded container, so it is not an AAR. */
    public Path file(String fileName, String content) throws IOException {
        Path file = write(root.resolve("deps").resolve(fileName), content);
        entries.add(new RuntimeEntry(fileName, file, false, null, "com.example", fileName, "1.0"));
        return file;
    }

    /** A real jar under {@code <root>/blobs/} holding one empty-ish class entry. */
    public Path jar(String fileName, String classEntry) throws IOException {
        Path path = Files.createDirectories(root.resolve("blobs")).resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            jos.putNextEntry(new JarEntry(classEntry));
            jos.write(new byte[] {0xC, 0xA});
            jos.closeEntry();
        }
        return path;
    }

    /** Write {@code text} at {@code file}, creating parents — the fixtures' one spelling. */
    public static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    // ---- what the body observed ----------------------------------------------------------------

    /** Progress labels the body emitted, in order. */
    public List<String> labels() {
        return List.copyOf(labels);
    }

    /** Diagnostics the body reported, in order, as {@code severity: file:line:col: message}. */
    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /** Extra produced paths the body declared, in order. */
    public List<Path> produced() {
        return List.copyOf(produced);
    }

    // ---- PackageIo + TaskExec ------------------------------------------------------------------

    @Override
    public Path classesDir() {
        return classesDir;
    }

    @Override
    public Path moduleDir() {
        return moduleDir;
    }

    @Override
    public Path scratch() {
        return root.resolve("scratch");
    }

    @Override
    public List<Path> runtimeClasspath() {
        List<Path> paths = new ArrayList<>();
        for (RuntimeEntry entry : entries) {
            @Nullable Path jar = entry.jar();
            if (jar != null) paths.add(jar);
        }
        return List.copyOf(paths);
    }

    /**
     * Append a jar that is on the compile classpath and not the runtime closure — a {@code
     * provided} dependency — carrying one entry, and return its path.
     */
    public Path compileOnly(String fileName, String entry) throws IOException {
        Path jar = jar(fileName, entry);
        compileOnly.add(jar);
        return jar;
    }

    /** The runtime entries' jars followed by the compile-only ones: what {@code javac} sees. */
    @Override
    public List<Path> compileClasspath() {
        List<Path> paths = new ArrayList<>(runtimeClasspath());
        paths.addAll(compileOnly);
        return List.copyOf(paths);
    }

    @Override
    public List<RuntimeEntry> runtimeEntries() {
        return List.copyOf(entries);
    }

    @Override
    public PluginConfig config() {
        return new PluginConfig(pluginId, config);
    }

    @Override
    public ProjectFacts project() {
        return project;
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
        return Path.of(Objects.requireNonNull(System.getProperty("java.home"), "java.home"));
    }

    @Override
    public boolean offline() {
        return offline;
    }

    @Override
    public void label(String text) {
        labels.add(text);
    }

    @Override
    public void diagnostic(String severity, @Nullable String file, int line, int col, String message) {
        StringBuilder b = new StringBuilder(severity).append(": ");
        if (file != null) {
            b.append(file);
            if (line > 0) b.append(':').append(line);
            if (col > 0) b.append(':').append(col);
            b.append(": ");
        }
        diagnostics.add(b.append(message).toString());
    }

    @Override
    public void produced(Path path) {
        produced.add(path);
    }

    // `tool` and `java` are identical defaults on both interfaces, so Java requires one explicit
    // resolution. They forward to the real thing rather than throwing: `SigningTest` drives jk's
    // signing step against a genuine `keytool` / `jarsigner` fork out of the test JVM's own JDK.

    @Override
    public TaskExec.ToolRun tool(String bin) {
        return PackageIo.super.tool(bin);
    }

    @Override
    public TaskExec.ToolRun tool(Path executable) {
        return PackageIo.super.tool(executable);
    }

    @Override
    public TaskExec.ToolRun java() {
        return PackageIo.super.java();
    }
}
