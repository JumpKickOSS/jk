// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * What the import reads out of an effective model's {@code <properties>} and {@code
 * <build><plugins>}: the compiler level and arguments, annotation processor paths, a toolchain
 * pin, the Kotlin plugin, the application main class and custom jar-manifest attributes. Values
 * are already interpolated; one that still carries {@code ${...}} had no definition anywhere in
 * the chain and is skipped.
 */
final class PluginFacts {

    /** The lowest {@code java =} jk compiles for; an older declared level is raised to it. */
    static final int JAVA_FLOOR = 17;

    /**
     * Plugins the import maps (or reports on its own terms); every other plugin gets the generic
     * row. os-maven-plugin's {@code detect} goal exports the {@code os.detected.*} properties the
     * effective model values from the host itself, so it has nothing to import.
     */
    static final Set<String> MAPPED_PLUGINS = Set.of(
            "maven-compiler-plugin",
            "os-maven-plugin",
            "maven-toolchains-plugin",
            "maven-resources-plugin",
            "build-helper-maven-plugin",
            "openapi-generator-maven-plugin",
            "protobuf-maven-plugin",
            "localizer-maven-plugin",
            "antlr4-maven-plugin",
            "wire-maven-plugin",
            "graphqlcodegen-maven-plugin",
            "graphql-codegen-maven-plugin",
            "maven-jar-plugin",
            "maven-source-plugin",
            "maven-javadoc-plugin",
            "kotlin-maven-plugin",
            "maven-surefire-plugin",
            "maven-failsafe-plugin",
            "jacoco-maven-plugin",
            "maven-shade-plugin",
            "maven-assembly-plugin",
            "spring-boot-maven-plugin",
            "git-commit-id-maven-plugin",
            "git-commit-id-plugin",
            "dokka-maven-plugin",
            "quarkus-maven-plugin",
            "native-maven-plugin",
            "jib-maven-plugin",
            "docker-maven-plugin",
            "maven-war-plugin");

    private static final String[] COMPILER_PROPERTIES = {
        "maven.compiler.release", "maven.compiler.target", "maven.compiler.source"
    };
    private static final String[] COMPILER_CONFIG = {"release", "target", "source"};
    private static final String[] MAIN_CLASS_PROPERTIES = {"start-class", "exec.mainClass", "main.class", "mainClass"};
    /** Compiler plugin switches and the javac flag each one means. */
    private static final List<Map.Entry<String, String>> COMPILER_SWITCHES = List.of(
            Map.entry("parameters", "-parameters"),
            Map.entry("enablePreview", "--enable-preview"),
            Map.entry("failOnWarning", "-Werror"));
    /** javac options that take the following token as their value and that {@code java =} already states. */
    private static final Set<String> LEVEL_OPTIONS = Set.of("--release", "-source", "-target", "--source", "--target");

    private PluginFacts() {}

    static List<Plugin> plugins(Model model) {
        Build build = model.getBuild();
        return build == null ? List.of() : build.getPlugins();
    }

    static Optional<Plugin> plugin(Model model, String artifactId) {
        return plugins(model).stream()
                .filter(p -> artifactId.equals(p.getArtifactId()))
                .findFirst();
    }

    /** A declared compiler level and the key that declared it ({@code maven.compiler.source}, {@code <release>}). */
    record CompilerLevel(int release, String origin) {}

    /** The Java level: {@code maven.compiler.*} properties, else the compiler plugin's configuration. */
    static Optional<CompilerLevel> compilerLevel(Model model) {
        Properties props = model.getProperties();
        for (String key : COMPILER_PROPERTIES) {
            Optional<Integer> level = javaLevel(props.getProperty(key));
            if (level.isPresent()) return Optional.of(new CompilerLevel(level.get(), "`" + key + "`"));
        }
        Optional<Plugin> compiler = plugin(model, "maven-compiler-plugin");
        if (compiler.isEmpty()) return Optional.empty();
        for (Xpp3Dom config : configurations(compiler.get())) {
            for (String key : COMPILER_CONFIG) {
                Optional<Integer> level = javaLevel(text(config.getChild(key)));
                if (level.isPresent()) return Optional.of(new CompilerLevel(level.get(), "`<" + key + ">`"));
            }
        }
        return Optional.empty();
    }

    /**
     * The compiler arguments each compile step gets: {@code main} is {@code [javac] args} and
     * {@code test} is {@code [javac.test] args}; {@code scoped} names each execution whose
     * configuration reached one step alone, as {@code `<id>` (goal `compile`)}.
     */
    record CompilerArgs(List<String> main, List<String> test, List<String> scoped) {
        /** Whether compile-test gets other arguments than compile-main, so the manifest needs both tables. */
        boolean split() {
            return !main.equals(test);
        }
    }

    /** Which compile step a compiler-plugin execution binds. */
    private enum CompileStep {
        MAIN,
        TEST,
        BOTH
    }

    /**
     * The compiler plugin's {@code <compilerArgs>} and switches, in order, minus the level options
     * {@code java =} states ({@code --release N}, {@code -source}, {@code -target}) — everything
     * else, {@code -A} processor options included, is a verbatim args entry. The plugin's own
     * {@code <configuration>} reaches both compile steps; an execution's reaches the step its goal
     * binds: {@code compile} (or the {@code default-compile} id) compile-main alone, {@code
     * testCompile} (or {@code default-testCompile}) compile-test alone, as Maven runs them.
     */
    static CompilerArgs compilerArgs(Model model) {
        List<String> main = new ArrayList<>();
        List<String> test = new ArrayList<>();
        List<String> scoped = new ArrayList<>();
        Optional<Plugin> compiler = plugin(model, "maven-compiler-plugin");
        if (compiler.isEmpty()) return new CompilerArgs(main, test, scoped);
        if (compiler.get().getConfiguration() instanceof Xpp3Dom config) {
            collectCompilerArgs(config, main);
            collectCompilerSwitches(config, main);
            collectCompilerArgs(config, test);
            collectCompilerSwitches(config, test);
        }
        for (PluginExecution execution : compiler.get().getExecutions()) {
            if (!(execution.getConfiguration() instanceof Xpp3Dom config)) continue;
            CompileStep step = compileStep(execution);
            if (step != CompileStep.TEST) {
                collectCompilerArgs(config, main);
                collectCompilerSwitches(config, main);
            }
            if (step != CompileStep.MAIN) {
                collectCompilerArgs(config, test);
                collectCompilerSwitches(config, test);
            }
            if (step != CompileStep.BOTH && config.getChild("compilerArgs") != null) scoped.add(label(execution, step));
        }
        return new CompilerArgs(main, test, scoped);
    }

    /** {@code `<id>` (goal `compile`)} for a compiler-plugin execution bound to one step. */
    private static String label(PluginExecution execution, CompileStep step) {
        return "`" + execution.getId() + "` (goal `" + (step == CompileStep.MAIN ? "compile" : "testCompile") + "`)";
    }

    /**
     * The step an execution's goals bind: {@code compile} without {@code testCompile} is
     * compile-main, {@code testCompile} without {@code compile} is compile-test; no goals fall back
     * to Maven's default execution ids, and anything else reaches both.
     */
    private static CompileStep compileStep(PluginExecution execution) {
        List<String> goals = execution.getGoals();
        boolean compile = goals.contains("compile");
        boolean testCompile = goals.contains("testCompile");
        if (compile && !testCompile) return CompileStep.MAIN;
        if (testCompile && !compile) return CompileStep.TEST;
        if (goals.isEmpty()) {
            if ("default-compile".equals(execution.getId())) return CompileStep.MAIN;
            if ("default-testCompile".equals(execution.getId())) return CompileStep.TEST;
        }
        return CompileStep.BOTH;
    }

    /**
     * The compiler-plugin executions bound to one step that declare {@code <annotationProcessorPaths>}
     * of their own, as {@code `<id>` (goal `compile`)}: jk's one {@code [processor-dependencies]}
     * table serves both compile steps, so the import writes the paths and says so.
     */
    static List<String> stepScopedProcessorPaths(Model model) {
        List<String> scoped = new ArrayList<>();
        Optional<Plugin> compiler = plugin(model, "maven-compiler-plugin");
        if (compiler.isEmpty()) return scoped;
        for (PluginExecution execution : compiler.get().getExecutions()) {
            if (!(execution.getConfiguration() instanceof Xpp3Dom config)) continue;
            CompileStep step = compileStep(execution);
            if (step != CompileStep.BOTH && config.getChild("annotationProcessorPaths") != null) {
                scoped.add(label(execution, step));
            }
        }
        return scoped;
    }

    /**
     * The compiler plugin's own boolean switches that stand for a javac flag: {@code <parameters>}
     * (set by the Spring Boot parent), {@code <enablePreview>} and {@code <failOnWarning>}. Each
     * lands once, whether the POM spelled it as a switch, in {@code <compilerArgs>}, or both.
     */
    static void collectCompilerSwitches(Xpp3Dom config, List<String> args) {
        for (Map.Entry<String, String> e : COMPILER_SWITCHES) {
            boolean on = EnvValues.parseBool(usable(text(config.getChild(e.getKey()))))
                    .orElse(false);
            if (on && !args.contains(e.getValue())) args.add(e.getValue());
        }
    }

    static void collectCompilerArgs(Xpp3Dom config, List<String> args) {
        Xpp3Dom list = config.getChild("compilerArgs");
        if (list == null) return;
        boolean skipValue = false;
        for (Xpp3Dom arg : list.getChildren()) {
            String v = usable(arg.getValue());
            if (v == null) continue;
            if (skipValue) {
                skipValue = false;
                continue;
            }
            if (LEVEL_OPTIONS.contains(v)) {
                skipValue = true;
                continue;
            }
            if (!args.contains(v)) args.add(v);
        }
    }

    /**
     * Every {@code <annotationProcessorPaths><path>} of the compiler plugin as a dependency. A path
     * without a version takes the effective {@code dependencyManagement} pin for its GA, the way the
     * compiler plugin itself resolves it; nothing there leaves the version unusable.
     */
    static List<Pom.Dep> annotationProcessorPaths(Model model) {
        List<Pom.Dep> paths = new ArrayList<>();
        Optional<Plugin> compiler = plugin(model, "maven-compiler-plugin");
        if (compiler.isEmpty()) return paths;
        for (Xpp3Dom config : configurations(compiler.get())) {
            Xpp3Dom list = config.getChild("annotationProcessorPaths");
            if (list == null) continue;
            for (Xpp3Dom path : list.getChildren()) {
                String group = usable(text(path.getChild("groupId")));
                String artifact = usable(text(path.getChild("artifactId")));
                if (group == null || artifact == null) continue;
                String version = usable(text(path.getChild("version")));
                if (version == null) version = managedVersion(model, group, artifact);
                paths.add(new Pom.Dep(
                        group,
                        artifact,
                        version,
                        null,
                        false,
                        usable(text(path.getChild("classifier"))),
                        null,
                        List.of()));
            }
        }
        return paths;
    }

    /** The effective {@code dependencyManagement} pin for {@code group:artifact}, when there is one. */
    static @Nullable String managedVersion(Model model, String group, String artifact) {
        DependencyManagement dm = model.getDependencyManagement();
        if (dm == null) return null;
        for (Dependency d : dm.getDependencies()) {
            if (group.equals(d.getGroupId()) && artifact.equals(d.getArtifactId())) return usable(d.getVersion());
        }
        return null;
    }

    /**
     * A deliberate toolchain pin: {@code maven-toolchains-plugin}'s {@code <toolchains><jdk>} or the
     * compiler plugin's {@code <jdkToolchain>}, as jk's {@code jdk} spec ({@code temurin-17},
     * {@code 17}). Empty when the POM names no toolchain — a compiler level alone is not a pin.
     */
    static Optional<String> toolchainJdk(Model model) {
        for (String artifactId : new String[] {"maven-toolchains-plugin", "maven-compiler-plugin"}) {
            Optional<Plugin> plugin = plugin(model, artifactId);
            if (plugin.isEmpty()) continue;
            for (Xpp3Dom config : configurations(plugin.get())) {
                List<Xpp3Dom> jdks = new ArrayList<>();
                visit(config, "jdkToolchain", jdks::add);
                visit(config, "toolchains", toolchains -> {
                    Xpp3Dom jdk = toolchains.getChild("jdk");
                    if (jdk != null) jdks.add(jdk);
                });
                for (Xpp3Dom jdk : jdks) {
                    Optional<String> spec = jdkSpec(jdk);
                    if (spec.isPresent()) return spec;
                }
            }
        }
        return Optional.empty();
    }

    /** {@code <version>17</version><vendor>temurin</vendor>} → {@code temurin-17}; a range keeps its lower bound. */
    private static Optional<String> jdkSpec(Xpp3Dom jdk) {
        String version = usable(text(jdk.getChild("version")));
        if (version == null) return Optional.empty();
        Optional<Integer> major = javaLevel(version.replaceFirst("^[\\[(]", "").split("[,)\\]]", 2)[0]);
        if (major.isEmpty()) return Optional.empty();
        String vendor = usable(text(jdk.getChild("vendor")));
        return Optional.of(vendor == null ? Integer.toString(major.get()) : vendor + "-" + major.get());
    }

    /** {@code 17} → 17; the {@code 1.8} spelling → 8. */
    static Optional<Integer> javaLevel(@Nullable String value) {
        if (value == null || value.isBlank() || value.contains("${")) return Optional.empty();
        String v = value.trim();
        if (v.startsWith("1.")) v = v.substring(2);
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A declared {@code kotlin-maven-plugin}; {@code version} is null when nothing in the chain pins it. */
    record Kotlin(@Nullable String version) {}

    /**
     * The Kotlin plugin when declared, with the plugin's own version, else the {@code
     * kotlin.version} property. Empty when the plugin is absent (a Java project).
     */
    static Optional<Kotlin> kotlin(Model model) {
        Optional<Plugin> plugin = plugin(model, "kotlin-maven-plugin");
        if (plugin.isEmpty()) return Optional.empty();
        String version = usable(plugin.get().getVersion());
        if (version == null) {
            Properties props = model.getProperties();
            version = usable(props.getProperty("kotlin.version"));
            if (version == null) version = usable(props.getProperty("kotlin.compiler.version"));
        }
        return Optional.of(new Kotlin(version));
    }

    /**
     * The application main class: the first {@code <mainClass>} in any plugin configuration
     * (jar / assembly / shade / exec / boot), else a {@code start-class}-style property.
     */
    static @Nullable String mainClass(Model model) {
        List<String> found = new ArrayList<>();
        for (Plugin plugin : plugins(model)) {
            for (Xpp3Dom config : configurations(plugin)) {
                visit(config, "mainClass", node -> {
                    String v = usable(node.getValue());
                    if (v != null) found.add(v);
                });
            }
            if (!found.isEmpty()) return found.getFirst();
        }
        for (String key : MAIN_CLASS_PROPERTIES) {
            String v = usable(model.getProperties().getProperty(key));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Custom jar-manifest attributes from every {@code <archive><manifestEntries>} (jar / assembly
     * / shade). {@code Main-Class} is excluded — it routes to {@code [application].main}.
     */
    static Map<String, String> manifestEntries(Model model) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Plugin plugin : plugins(model)) {
            for (Xpp3Dom config : configurations(plugin)) {
                visit(config, "manifestEntries", entries -> {
                    for (Xpp3Dom entry : entries.getChildren()) {
                        String value = usable(entry.getValue());
                        if (value == null || entry.getName().equalsIgnoreCase("Main-Class")) continue;
                        attrs.put(entry.getName(), value);
                    }
                });
            }
        }
        return attrs;
    }

    /**
     * The Maven profile whose declaration is what makes {@code plugin} do anything: the effective
     * model declares it bare (no executions, no configuration of its own) and a profile of this POM
     * or of an ancestor carries its executions or configuration under {@code <plugins>} or {@code
     * <pluginManagement>}. That profile is not active here, else its payload would be in the
     * effective model, so under Maven the plugin runs only with {@code -P}. Empty when the plugin
     * carries executions or configuration itself, or when no profile adds any.
     */
    static Optional<String> boundOnlyInProfile(EffectiveModel em, Plugin plugin) {
        if (bound(plugin)) return Optional.empty();
        String artifactId = plugin.getArtifactId();
        List<Model> chain = new ArrayList<>();
        chain.add(em.raw());
        for (EffectiveModel.Ancestor ancestor : em.ancestors()) chain.add(ancestor.raw());
        for (Model model : chain) {
            for (Profile profile : model.getProfiles()) {
                if (profile.getBuild() != null && bindsPlugin(profile.getBuild(), artifactId)) {
                    String id = profile.getId();
                    return Optional.of(id == null || id.isBlank() ? "<unnamed>" : id);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the plugin carries an execution or a configuration of its own. */
    private static boolean bound(Plugin plugin) {
        return !plugin.getExecutions().isEmpty()
                || (plugin.getConfiguration() instanceof Xpp3Dom dom && dom.getChildCount() > 0);
    }

    /** Whether {@code build}'s plugins or plugin management carry a bound declaration of {@code artifactId}. */
    private static boolean bindsPlugin(BuildBase build, String artifactId) {
        List<Plugin> declared = new ArrayList<>(build.getPlugins());
        if (build.getPluginManagement() != null)
            declared.addAll(build.getPluginManagement().getPlugins());
        return declared.stream().anyMatch(p -> artifactId.equals(p.getArtifactId()) && bound(p));
    }

    /** The plugin's configuration followed by each execution's, in declaration order. */
    static List<Xpp3Dom> configurations(Plugin plugin) {
        List<Xpp3Dom> out = new ArrayList<>();
        if (plugin.getConfiguration() instanceof Xpp3Dom dom) out.add(dom);
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getConfiguration() instanceof Xpp3Dom dom) out.add(dom);
        }
        return out;
    }

    /** Depth-first over {@code node}, calling {@code sink} on every element named {@code name}. */
    static void visit(Xpp3Dom node, String name, Consumer<Xpp3Dom> sink) {
        if (name.equals(node.getName())) {
            sink.accept(node);
            return;
        }
        for (Xpp3Dom child : node.getChildren()) visit(child, name, sink);
    }

    static @Nullable String text(@Nullable Xpp3Dom node) {
        return node == null ? null : node.getValue();
    }

    /** The usable text of {@code parent}'s child {@code name}; null when absent, blank or a placeholder. */
    static @Nullable String child(@Nullable Xpp3Dom parent, String name) {
        return parent == null ? null : usable(text(parent.getChild(name)));
    }

    /** Trimmed, or null when blank or still a {@code ${...}} placeholder. */
    static @Nullable String usable(@Nullable String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() || v.contains("${") ? null : v;
    }
}
