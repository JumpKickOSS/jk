// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static cc.jumpkick.gradle.GradleScriptText.STR;
import static cc.jumpkick.gradle.GradleScriptText.extractBlock;
import static cc.jumpkick.gradle.GradleScriptText.firstNonNull;
import static cc.jumpkick.gradle.GradleScriptText.firstString;
import static cc.jumpkick.gradle.GradleScriptText.stripComments;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Best-effort string-level scanner for {@code build.gradle[.kts]} declarative idioms. Emits
 * {@link ImportReport} notes for programmatic Gradle it cannot map.
 */
public final class GradleImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    private static final Pattern GROUP_ASSIGN = Pattern.compile("(?m)^\\s*group\\s*[=]?\\s*" + STR);
    private static final Pattern VERSION_ASSIGN = Pattern.compile("(?m)^\\s*version\\s*[=]?\\s*" + STR);
    // description = "..." / description "..." (optionally project.description).
    private static final Pattern DESCRIPTION_ASSIGN =
            Pattern.compile("(?m)^\\s*(?:project\\.)?description\\s*[=]?\\s*" + STR);
    private static final Pattern ROOT_NAME = Pattern.compile("(?m)^\\s*rootProject\\.name\\s*=\\s*" + STR);

    // plugins { id("...") version "..." ; id 'foo' ; kotlin("jvm") ; java ; application }
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*\\(?\\s*" + STR + "\\s*\\)?");
    private static final Pattern PLUGIN_KOTLIN = Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)");
    // kotlin("jvm") version "2.3.21" — version is groups 3/4 (after the plugin arg).
    private static final Pattern KOTLIN_PLUGIN_VERSION =
            Pattern.compile("kotlin\\s*\\(\\s*" + STR + "\\s*\\)\\s*version\\s*" + STR);
    // id("org.jetbrains.kotlin.jvm") [version "2.3.21"]
    private static final Pattern KOTLIN_ID = Pattern.compile("id\\s*\\(?\\s*[\"']org\\.jetbrains\\.kotlin[^\"']*[\"']");
    private static final Pattern KOTLIN_ID_VERSION =
            Pattern.compile("id\\s*\\(\\s*[\"']org\\.jetbrains\\.kotlin[^\"']*[\"']\\s*\\)\\s*version\\s*" + STR);

    /** One installed plugin's Gradle-import mapping: which table + config key a plugin id feeds. */
    private record PluginImportRule(
            String manifestId,
            @Nullable String versionTo,
            @Nullable String missingVersionWarning) {}

    /** Gradle plugin id → import rule, from every installed manifest's [[import.gradle-plugin]]. */
    private static Map<String, PluginImportRule> pluginImportRules() {
        Map<String, PluginImportRule> rules = new LinkedHashMap<>();
        for (var manifest : PluginTableRegistry.manifests()) {
            for (var rule : manifest.gradleImports()) {
                rules.put(
                        rule.id(), new PluginImportRule(manifest.id(), rule.versionTo(), rule.missingVersionWarning()));
            }
        }
        return rules;
    }

    /**
     * Evaluate the import rules against the plugins block: a rule with {@code version-to} maps
     * the Gradle plugin's inline version into the owned table's config (only that key — schema
     * defaults are exactly what the renderer omits, so the round trip stays minimal); declared
     * without a version, the rule's warning is reported instead. Version-less rules are
     * recognition-only (their construct is absorbed by another contribution, e.g. Boot's BOM
     * auto-import covering dependency-management).
     */
    private static List<PluginConfig> mapPluginTables(
            String pluginsBody, Map<String, PluginImportRule> rules, ImportReport.Builder report) {
        List<PluginConfig> out = new ArrayList<>();
        for (Map.Entry<String, PluginImportRule> e : rules.entrySet()) {
            if (!pluginsBody.contains(e.getKey())) continue;
            PluginImportRule rule = e.getValue();
            String versionTo = rule.versionTo();
            if (versionTo == null) continue; // recognition-only
            Pattern versionPattern = Pattern.compile(
                    "id\\s*\\(?\\s*[\"']" + Pattern.quote(e.getKey()) + "[\"']\\s*\\)?\\s*version\\s*" + STR);
            Matcher m = versionPattern.matcher(pluginsBody);
            if (m.find()) {
                out.add(new PluginConfig(
                        rule.manifestId(),
                        Map.of(versionTo, Objects.requireNonNull(firstNonNull(m.group(1), m.group(2))))));
            } else if (rule.missingVersionWarning() != null) {
                report.warning(rule.missingVersionWarning());
            }
        }
        return out;
    }

    /** The Gradle plugin that writes {@code git.properties}; the {@code [build-info]} table in jk. */
    private static final String GIT_PROPERTIES_PLUGIN = "com.gorylenko.gradle-git-properties";

    /** The Dokka Gradle plugin; its inline version is the {@code [dokka]} pin. */
    private static final String DOKKA_PLUGIN = "org.jetbrains.dokka";

    private static final Pattern DOKKA_ID_VERSION =
            Pattern.compile("id\\s*\\(?\\s*[\"']" + Pattern.quote(DOKKA_PLUGIN) + "[\"']\\s*\\)?\\s*version\\s*" + STR);

    /** {@code springBoot { buildInfo() }} — Boot's own {@code build-info.properties}. */
    private static final Pattern BOOT_BUILD_INFO = Pattern.compile("\\bbuildInfo\\s*\\(");

    // application { mainClass.set("X") } / mainClass = "X" — groups 1/2 (set) or 3/4 (=).
    private static final Pattern APPLICATION_MAIN_CLASS =
            Pattern.compile("mainClass\\s*(?:\\.set\\s*\\(\\s*" + STR + "\\s*\\)|=\\s*" + STR + ")");
    // Groovy / older DSL: mainClassName = "X".
    private static final Pattern MAIN_CLASS_NAME = Pattern.compile("(?m)^\\s*mainClassName\\s*[=]?\\s*" + STR);

    // A manifest attribute pair inside a `manifest { attributes(...) }` block, in
    // either Kotlin (`"K" to V`) or Groovy (`"K": V`) form. Value is a string
    // literal (groups 3/4) or a bare expression like project.version (group 5).
    private static final Pattern MANIFEST_ATTR =
            Pattern.compile(STR + "\\s*(?:to|:)\\s*(?:" + STR + "|([A-Za-z_][\\w.]*))");

    // java { sourceCompatibility = JavaVersion.VERSION_25 }
    private static final Pattern JAVA_VERSION_TOKEN =
            Pattern.compile("JavaVersion\\.VERSION_([0-9_]+)|JavaLanguageVersion\\.of\\(\\s*([0-9]+)\\s*\\)"
                    + "|sourceCompatibility\\s*[=]?\\s*['\"]?([0-9.]+)['\"]?"
                    + "|jvmToolchain\\s*\\(\\s*([0-9]+)\\s*\\)");

    // repositories: maven { url = uri("https://...") } or maven { url 'https://...' }
    private static final Pattern MAVEN_URL =
            Pattern.compile("maven\\s*\\{[^}]*?url\\s*[=]?\\s*(?:uri\\s*\\(\\s*)?" + STR);

    private GradleImporter() {}

    public static Result importFrom(Path script) throws IOException {
        String text = Files.readString(script);
        Path projectDir = Objects.requireNonNull(script.toAbsolutePath().getParent());
        String defaultArtifact = projectName(projectDir);
        GradleVersionCatalog catalog =
                GradleVersionCatalog.forProject(projectDir).orElse(null);
        return importFromString(text, defaultArtifact, catalog);
    }

    public static Result importFromString(String text, String defaultArtifact) {
        return importFromString(text, defaultArtifact, null);
    }

    public static Result importFromString(String text, String defaultArtifact, @Nullable GradleVersionCatalog catalog) {
        String stripped = stripComments(text);
        ImportReport.Builder report = ImportReport.builder();
        // Surface catalog parse notes (unresolved version.ref, …) before mapping deps.
        if (catalog != null) {
            for (String note : catalog.parseNotes()) {
                report.warning(note);
            }
        }

        String group = firstString(GROUP_ASSIGN, stripped).orElse("com.example");
        String version = firstString(VERSION_ASSIGN, stripped).orElse("0.1.0");
        String description = firstString(DESCRIPTION_ASSIGN, stripped).orElse(null);
        int jdk = detectJdk(stripped).flatMap(GradleImporter::parseInt).orElse(25);

        // plugins block — the Kotlin plugin marks a Kotlin project (and carries
        // its compiler version); ids claimed by an installed jk plugin's [[import.gradle-plugin]]
        // rules map to that plugin's table below; the rest are diagnostics only.
        String pluginsBody = extractBlock(stripped, "plugins").orElse("");
        VersionSelector kotlin = detectKotlinVersion(pluginsBody, report);
        // git.properties from the git-properties plugin, build-info.properties from Boot's
        // `springBoot { buildInfo() }`: both are the [build-info] table.
        BuildBlock.BuildInfo buildInfo = BOOT_BUILD_INFO.matcher(stripped).find() ? BuildBlock.BuildInfo.DEFAULT : null;
        BuildBlock.Dokka dokkaTable = BuildBlock.Dokka.DEFAULT;
        Map<String, PluginImportRule> importRules = pluginImportRules();
        for (Matcher m = PLUGIN_ID.matcher(pluginsBody); m.find(); ) {
            String pluginId = firstNonNull(m.group(1), m.group(2));
            if (pluginId == null || pluginId.isBlank()) continue;
            if (pluginId.startsWith("org.jetbrains.kotlin")) continue; // handled as a Kotlin project
            switch (pluginId) {
                case "java", "java-library", "application" -> {
                    // implicit in jk — nothing to say.
                }
                case GIT_PROPERTIES_PLUGIN -> buildInfo = BuildBlock.BuildInfo.DEFAULT;
                case DOKKA_PLUGIN -> {
                    // Dokka's version is the [dokka] pin; applied without one, jk's default Dokka runs.
                    Matcher dokka = DOKKA_ID_VERSION.matcher(pluginsBody);
                    if (dokka.find()) {
                        String v = Objects.requireNonNull(firstNonNull(dokka.group(1), dokka.group(2)));
                        dokkaTable = new BuildBlock.Dokka(VersionSelector.parse(v), BuildBlock.Dokka.Format.JAVADOC);
                    }
                }
                default -> {
                    if (!importRules.containsKey(pluginId)) {
                        report.warning(
                                "Gradle plugin `"
                                        + pluginId
                                        + "` not yet mapped."
                                        + " Plugin-aware mappings (Spring Boot, Quarkus, Spotless, ...) arrive in a later slice.");
                    }
                }
            }
        }

        // Jar-manifest attributes from `jar { manifest { attributes(...) } }`
        // (or tasks.jar / tasks.named("jar")). Main-Class routes to [application].main.
        Map<String, String> manifest = detectManifestAttributes(stripped, group, defaultArtifact, version, report);
        String mainClass = detectMainClass(stripped);
        String manifestMain = manifest.remove("Main-Class");
        if (mainClass == null) mainClass = manifestMain;

        // Plugin-owned tables: each installed jk plugin's [[import.gradle-plugin]] rules map a
        // Gradle plugin id to its table (Boot: the Gradle plugin's version IS the Boot version ->
        // `version`, which auto-imports the BOM so versionless starters stay versionless).
        // Applied-without-version (settings pluginManagement) can't be resolved from this file
        // alone -- the rule's warning asks the user to fill it in.
        List<PluginConfig> pluginConfigs = mapPluginTables(pluginsBody, importRules, report);

        Map<Scope, List<Dependency>> deps = GradleDependencies.parse(stripped, catalog, report);
        List<RepositorySpec> repos = parseRepositories(stripped, report);
        warnUnsupportedSections(stripped, report);

        // A Kotlin project sets `kotlin` (a version) and leaves `java` at 0 —
        // the two are mutually exclusive. javaRelease() falls back to jdk.
        int java = kotlin != null ? 0 : jdk;
        Project project = Project.builder(group, defaultArtifact, version)
                .jdkMajor(jdk)
                .java(java)
                .kotlin(kotlin)
                .description(description)
                .build();
        JkBuild.Application application = mainClass != null ? new JkBuild.Application(mainClass, false) : null;
        JkBuild.Builder builder = JkBuild.builder(project)
                .dependencies(new JkBuild.Dependencies(deps))
                .repositories(repos)
                .application(application)
                .build(BuildBlock.EMPTY.withBuildInfo(buildInfo).withDokka(dokkaTable));
        for (PluginConfig config : pluginConfigs) {
            builder.pluginConfig(config);
        }
        JkBuild jkBuild = builder.build();
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
    }

    /**
     * Parse manifest attributes from a {@code manifest { attributes(...) }} block (inside {@code
     * jar}/{@code tasks.jar}/{@code tasks.named("jar")}). Supports Kotlin ({@code "K" to V}) and
     * Groovy ({@code "K": V}) forms. String-literal values pass through; the common Gradle
     * expressions {@code project.version}/{@code name}/{@code group} resolve to the imported
     * coordinates; other expressions are skipped with a report note. The returned map preserves
     * declaration order and may contain {@code Main-Class} (the caller routes it to {@code
     * [application].main}).
     */
    private static Map<String, String> detectManifestAttributes(
            String text, String group, String artifact, String version, ImportReport.Builder report) {
        String body = extractBlock(text, "manifest").orElse(null);
        if (body == null) return new LinkedHashMap<>();
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Matcher m = MANIFEST_ATTR.matcher(body); m.find(); ) {
            String key = firstNonNull(m.group(1), m.group(2));
            if (key == null) continue;
            String literal = firstNonNull(m.group(3), m.group(4));
            String value;
            if (literal != null) {
                value = literal;
            } else {
                String expr = m.group(5);
                value = resolveGradleExpr(expr, group, artifact, version);
                if (value == null) {
                    report.warning("manifest attribute `"
                            + key
                            + "` uses the Gradle expression `"
                            + expr
                            + "`, which jk import can't resolve; the attribute was dropped."
                            + " Add it to the [manifest] table in jk.toml if needed.");
                    continue;
                }
            }
            attrs.put(key, value);
        }
        return attrs;
    }

    /** Resolve common Gradle property expressions used in manifest values; null if unknown. */
    private static @Nullable String resolveGradleExpr(
            @Nullable String expr, String group, String artifact, String version) {
        if (expr == null) return null;
        String lower = expr.toLowerCase(Locale.ROOT);
        if (lower.endsWith("version")) return version;
        if (lower.endsWith("name")) return artifact;
        if (lower.endsWith("group")) return group;
        return null;
    }

    /**
     * Detect the Kotlin compiler version from the {@code plugins {}} block. Recognises {@code
     * kotlin("jvm") version "X"} and {@code id("org.jetbrains.kotlin.jvm") version "X"}; the declared
     * version is the pin. When the Kotlin plugin is applied without an explicit version the selector
     * is {@code latest}, which the first {@code jk lock} resolves. Returns {@code null} for a
     * non-Kotlin (Java) project.
     */
    private static @Nullable VersionSelector detectKotlinVersion(String pluginsBody, ImportReport.Builder report) {
        Matcher m = KOTLIN_PLUGIN_VERSION.matcher(pluginsBody);
        if (m.find()) {
            return VersionSelector.parse(Objects.requireNonNull(firstNonNull(m.group(3), m.group(4))));
        }
        Matcher mid = KOTLIN_ID_VERSION.matcher(pluginsBody);
        if (mid.find()) {
            return VersionSelector.parse(Objects.requireNonNull(firstNonNull(mid.group(1), mid.group(2))));
        }
        boolean kotlinApplied = PLUGIN_KOTLIN.matcher(pluginsBody).find()
                || KOTLIN_ID.matcher(pluginsBody).find();
        if (kotlinApplied) {
            report.warning("Kotlin plugin recognised without an explicit version; project.kotlin is"
                    + " `latest` — `jk lock` picks the current stable, then `jk update` moves it.");
            return VersionSelector.parse("latest");
        }
        return null;
    }

    /**
     * Extract the application main class from {@code application { mainClass.set("X") }} / {@code
     * mainClass = "X"} (Kotlin DSL) or a top-level {@code mainClassName = "X"} (Groovy). Returns
     * {@code null} when none is declared.
     */
    private static @Nullable String detectMainClass(String text) {
        String appBody = extractBlock(text, "application").orElse(null);
        if (appBody != null) {
            Matcher m = APPLICATION_MAIN_CLASS.matcher(appBody);
            if (m.find()) {
                String v = firstNonNull(m.group(1), m.group(2), m.group(3), m.group(4));
                if (v != null) return v;
            }
        }
        Matcher anywhere = APPLICATION_MAIN_CLASS.matcher(text);
        if (anywhere.find()) {
            String v = firstNonNull(anywhere.group(1), anywhere.group(2), anywhere.group(3), anywhere.group(4));
            if (v != null) return v;
        }
        Matcher legacy = MAIN_CLASS_NAME.matcher(text);
        if (legacy.find()) {
            return firstNonNull(legacy.group(1), legacy.group(2));
        }
        return null;
    }

    /**
     * The project's name: {@code rootProject.name} from the {@code settings.gradle(.kts)} beside the
     * build file, else the directory's name, else {@code app}.
     */
    static String projectName(Path projectDir) throws IOException {
        for (String settings : SETTINGS_FILES) {
            Optional<String> named = readRootProjectName(projectDir.resolve(settings));
            if (named.isPresent()) return named.get();
        }
        Path dirName = projectDir.toAbsolutePath().getFileName();
        return dirName == null || dirName.toString().isEmpty() ? "app" : dirName.toString();
    }

    /** The settings file names a Gradle build root carries, Kotlin DSL first. */
    public static final List<String> SETTINGS_FILES = List.of("settings.gradle.kts", "settings.gradle");

    // --- repositories block -------------------------------------------------

    private static List<RepositorySpec> parseRepositories(String text, ImportReport.Builder report) {
        String body = extractBlock(text, "repositories").orElse(null);
        if (body == null) return List.of();
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Matcher m = MAVEN_URL.matcher(body); m.find(); ) {
            String url = firstNonNull(m.group(1), m.group(2));
            if (url == null || url.isBlank()) continue;
            // Skip the implicit Central — declaring it adds nothing.
            if (url.startsWith("https://repo.maven.apache.org/") || url.startsWith("https://repo1.maven.org/"))
                continue;
            try {
                String name = "repo" + (deduped.size() + 1);
                deduped.put(name, new RepositorySpec(name, RepositorySpec.normalizedUrl(new URI(url.trim()))));
            } catch (URISyntaxException e) {
                report.warning("repository URL `" + url + "` is not a valid URI; skipped.");
            }
        }
        if (body.contains("mavenLocal()")) {
            report.warning("`mavenLocal()` recognised but not mapped — jk reads ~/.m2 by default.");
        }
        return new ArrayList<>(deduped.values());
    }

    // --- java / kotlin toolchain --------------------------------------------

    private static Optional<String> detectJdk(String text) {
        Matcher m = JAVA_VERSION_TOKEN.matcher(text);
        if (!m.find()) return Optional.empty();
        String raw = firstNonNull(m.group(1), m.group(2), m.group(3), m.group(4));
        if (raw == null || raw.isBlank()) return Optional.empty();
        // VERSION_21 / VERSION_1_8 → "21" / "1.8"
        String normalized = raw.replace('_', '.');
        if (normalized.startsWith("1.")) {
            return Optional.of(normalized);
        }
        // Strip a leading "1." vestige if present.
        return Optional.of(normalized);
    }

    // --- catch-all tier 3 warnings ------------------------------------------

    private static final String[] TIER3_KEYWORDS = {
        "subprojects",
        "allprojects",
        "afterEvaluate",
        "beforeEvaluate",
        "configurations",
        "tasks.register",
        "task ",
        "withType",
        "ext {",
        "buildscript",
    };

    private static void warnUnsupportedSections(String text, ImportReport.Builder report) {
        for (String kw : TIER3_KEYWORDS) {
            if (text.contains(kw)) {
                report.error("script uses `"
                        + kw.trim()
                        + "` — programmatic Gradle blocks cannot be imported automatically."
                        + " Convert the intent to a jk profile/feature/task by hand.");
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private static Optional<Integer> parseInt(@Nullable String s) {
        if (s == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Read {@code rootProject.name} from a {@code settings.gradle(.kts)} if present. */
    public static Optional<String> readRootProjectName(Path settings) throws IOException {
        if (!Files.exists(settings)) return Optional.empty();
        return firstString(ROOT_NAME, stripComments(Files.readString(settings)));
    }
}
