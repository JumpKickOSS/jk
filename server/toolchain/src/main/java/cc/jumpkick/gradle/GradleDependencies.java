// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static cc.jumpkick.gradle.GradleScriptText.STR;
import static cc.jumpkick.gradle.GradleScriptText.firstNonNull;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The scanner's reading of a {@code dependencies { }} block: one entry per configuration call,
 * whether it is written {@code implementation("g:a:v")}, Groovy's {@code implementation 'g:a:v'},
 * a comma list of coordinates, a version-catalog accessor, or any of those followed by a
 * configuration closure ({@code { because … }}, {@code { exclude … }}). Each entry lands in the jk
 * table its configuration means; what cannot be mapped is a report row, never a silent drop.
 */
final class GradleDependencies {

    private static final Pattern ENTRY = Pattern.compile("^(?<config>[a-zA-Z][a-zA-Z0-9_]*)\\s*(?<rest>.*)$");
    private static final Pattern CATALOG_ACCESSOR = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern CALL = Pattern.compile("^(?<fn>[A-Za-z]+)\\s*\\((?<arg>.*)\\)$");
    private static final Pattern BARE_STRING = Pattern.compile("^\\s*" + STR + "\\s*$");
    // exclude group: 'g', module: 'm'  /  exclude(group = "g", module = "m")  /  exclude module: 'm'
    private static final Pattern EXCLUDE = Pattern.compile("exclude\\s*\\(?\\s*(?<pairs>[^)\\n]*)");
    private static final Pattern EXCLUDE_SIDE = Pattern.compile("(?<key>group|module)\\s*[:=]\\s*" + STR);
    private static final Pattern INTRANSITIVE = Pattern.compile("(?:isTransitive|transitive)\\s*=\\s*false");

    private final @Nullable GradleVersionCatalog catalog;
    private final GradleProperties properties;
    private final RefreshVersions refreshVersions;
    private final ImportReport.Builder report;
    private final Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
    /** {@code testCompileOnly} coordinates, named once in the row explaining where they went. */
    private final List<String> testCompileOnly = new ArrayList<>();

    private GradleDependencies(
            @Nullable GradleVersionCatalog catalog,
            GradleProperties properties,
            RefreshVersions refreshVersions,
            ImportReport.Builder report) {
        this.catalog = catalog;
        this.properties = properties;
        this.refreshVersions = refreshVersions;
        this.report = report;
    }

    /**
     * Every dependency the block declares, by jk scope; empty when the script has no block. {@code
     * properties} resolves the {@code $property} placeholders in coordinates; {@code
     * refreshVersions} answers a {@code _} version.
     */
    static Map<Scope, List<Dependency>> parse(
            String script,
            @Nullable GradleVersionCatalog catalog,
            GradleProperties properties,
            RefreshVersions refreshVersions,
            ImportReport.Builder report) {
        GradleDependencies deps = new GradleDependencies(catalog, properties, refreshVersions, report);
        GradleScriptText.extractBlock(script, "dependencies").ifPresent(body -> deps.parseBlock(body, false));
        if (!deps.testCompileOnly.isEmpty()) {
            report.warning("`testCompileOnly` dependencies " + String.join(", ", deps.testCompileOnly)
                    + " are written to [test-dependencies]: jk has no test-provided table, so they are on the"
                    + " test runtime classpath too.");
        }
        return deps.byScope;
    }

    /**
     * Walk the block line by line. An entry whose line ends in {@code {} opens a closure whose body
     * is collected until its brace closes and read for {@code exclude} rules; a {@code constraints
     * { }} block's entries are managed versions, whatever configuration they name.
     */
    private void parseBlock(String body, boolean managed) {
        String[] lines = body.split("\\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            i++;
            if (line.isEmpty() || line.equals("{") || line.equals("}")) continue;
            Matcher m = ENTRY.matcher(line);
            if (!m.matches()) {
                reportNotUnderstood(line);
                continue;
            }
            String configuration = m.group("config");
            String rest = m.group("rest").trim();
            String closure = null;
            if (rest.endsWith("{")) {
                rest = rest.substring(0, rest.length() - 1).trim();
                StringBuilder collected = new StringBuilder();
                i = collectClosure(lines, i, collected);
                closure = collected.toString();
            }
            if (configuration.equals("constraints") && rest.isEmpty() && closure != null) {
                parseBlock(closure, true);
                continue;
            }
            parseEntry(configuration, rest, closure, managed, line);
        }
    }

    /** Collect the lines of a closure opened on the previous line; returns the index after its close. */
    private static int collectClosure(String[] lines, int from, StringBuilder into) {
        int depth = 1;
        int i = from;
        while (i < lines.length && depth > 0) {
            String line = lines[i];
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) == '{') depth++;
                else if (line.charAt(c) == '}') depth--;
            }
            if (depth > 0) into.append(line).append('\n');
            else into.append(line, 0, Math.max(0, line.lastIndexOf('}'))).append('\n');
            i++;
        }
        return i;
    }

    private void parseEntry(String configuration, String rest, @Nullable String closure, boolean managed, String line) {
        Scope scope = managed ? Scope.MANAGED : mapConfiguration(configuration);
        if (scope == null) {
            report.error("Gradle configuration `" + configuration + "` is not a recognised jk scope; entry dropped (`"
                    + line + "`).");
            return;
        }
        String args = rest;
        if (args.startsWith("(") && args.endsWith(")"))
            args = args.substring(1, args.length() - 1).trim();
        if (args.isEmpty()) {
            reportNotUnderstood(line);
            return;
        }
        List<String> exclusions = closure == null ? List.of() : exclusions(closure);
        for (String arg : GradleScriptText.splitArguments(args)) {
            int before = count();
            parseArgument(configuration, scope, arg, line);
            if (!exclusions.isEmpty() && count() > before) withExclusions(scope, exclusions);
        }
        if (scope == Scope.TEST && configuration.equals("testCompileOnly")) {
            for (String arg : GradleScriptText.splitArguments(args)) {
                Matcher bare = BARE_STRING.matcher(arg);
                if (bare.find())
                    testCompileOnly.add(Objects.requireNonNull(firstNonNull(bare.group(1), bare.group(2))));
            }
        }
    }

    private int count() {
        int n = 0;
        for (List<Dependency> list : byScope.values()) n += list.size();
        return n;
    }

    /** Put {@code exclusions} on the dependency added last under {@code scope}. */
    private void withExclusions(Scope scope, List<String> exclusions) {
        List<Dependency> list = byScope.get(scope);
        if (list == null || list.isEmpty()) return;
        Dependency last = list.getLast();
        list.set(list.size() - 1, last.withExclusions(exclusions));
    }

    /** The {@code group:module} exclusions a configuration closure declares; {@code *:*} for {@code isTransitive = false}. */
    private List<String> exclusions(String closure) {
        List<String> out = new ArrayList<>();
        for (Matcher m = EXCLUDE.matcher(closure); m.find(); ) {
            String group = null;
            String module = null;
            for (Matcher side = EXCLUDE_SIDE.matcher(m.group("pairs")); side.find(); ) {
                String value = firstNonNull(side.group(2), side.group(3));
                if (side.group("key").equals("group")) group = value;
                else module = value;
            }
            if (group == null && module == null) continue;
            out.add((group == null ? "*" : group) + ":" + (module == null ? "*" : module));
        }
        if (INTRANSITIVE.matcher(closure).find()) out.add("*:*");
        return out;
    }

    private void parseArgument(String configuration, Scope scope, String arg, String line) {
        Matcher bare = BARE_STRING.matcher(arg);
        if (bare.find()) {
            addDependency(scope, Objects.requireNonNull(firstNonNull(bare.group(1), bare.group(2))));
            return;
        }
        if (CATALOG_ACCESSOR.matcher(arg).matches()) {
            resolveCatalogAccessor(arg, scope);
            return;
        }
        Matcher call = CALL.matcher(arg);
        if (!call.matches()) {
            report.error("complex dependency expression `" + line + "` not understood;"
                    + " re-state as a string-form coord in jk.toml.");
            return;
        }
        String inner = call.group("arg").trim();
        switch (call.group("fn")) {
            case "platform", "enforcedPlatform" -> {
                Matcher s = BARE_STRING.matcher(inner);
                if (s.find())
                    addDependency(Scope.PLATFORM, Objects.requireNonNull(firstNonNull(s.group(1), s.group(2))));
                else if (CATALOG_ACCESSOR.matcher(inner).matches()) resolveCatalogAccessor(inner, Scope.PLATFORM);
                else
                    report.error("complex dependency expression `" + line + "` not understood;"
                            + " re-state as a string-form coord in jk.toml.");
            }
            case "project" ->
                report.warning(
                        "Project dependency `" + literal(inner) + "` on configuration `"
                                + configuration
                                + "` was not mapped. Convert via a jk workspace module reference once you import the sibling module.");
            case "kotlin" -> {
                String token = literal(inner);
                report.warning("Kotlin shortcut `kotlin(\"" + token + "\")` was not mapped to a concrete coord."
                        + " Add the explicit `org.jetbrains.kotlin:kotlin-" + token + ":<version>` to jk.toml.");
            }
            case "files", "fileTree", "testFixtures" ->
                report.warning("dependency `" + arg + "` on configuration `" + configuration
                        + "` has no coordinate to import; dropped.");
            default ->
                report.error("complex dependency expression `" + line + "` not understood;"
                        + " re-state as a string-form coord in jk.toml.");
        }
    }

    private static String literal(String inner) {
        Matcher s = BARE_STRING.matcher(inner);
        return s.find() ? Objects.requireNonNull(firstNonNull(s.group(1), s.group(2))) : inner;
    }

    private void reportNotUnderstood(String line) {
        report.error("dependencies entry not understood: `" + line
                + "` — jk import is best-effort and only handles string-form deps."
                + " Re-state the dep in jk.toml under [dependencies] as `\"g:a\" = { version = \"=v\" }`.");
    }

    void addDependency(Scope scope, String rawCoord) {
        if (rawCoord.isBlank()) return;
        GradleProperties.Interpolated interpolated = properties.interpolate(rawCoord);
        String coord = interpolated.text();
        String[] parts = coord.split(":");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            report.error("dependency coord `" + coord + "` is not `group:artifact:version`; dropped.");
            return;
        }
        String module = parts[0] + ":" + parts[1];
        if (module.indexOf('$') >= 0) {
            report.error("dependency `" + rawCoord + "` names the Gradle propert" + plural(interpolated.unresolved())
                    + " in its coordinate, which neither gradle.properties nor the build script defines; dropped."
                    + " Declare the coordinate directly in jk.toml.");
            return;
        }
        String shortName = shortNameFor(module).orElse(parts[1]);
        if (parts.length == 2) {
            // Version-less `g:a` -- normal in Boot builds, where the plugin's BOM manages the
            // version. jk models it as platform-managed; [spring-boot] (or an explicit
            // [platform-dependencies] BOM) supplies the pin at resolve time.
            byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(Dependency.platformManaged(shortName, module));
            return;
        }
        String versionToken = parts[2];
        if (parts.length > 3) {
            report.warning("classifier/type on `" + coord + "` dropped; jk support arrives in a later slice.");
        }
        if (versionToken.indexOf('$') >= 0) {
            report.warning("dependency `" + module + "` takes its version from the Gradle propert"
                    + plural(interpolated.unresolved())
                    + ", which neither gradle.properties nor the build script defines; written without a version"
                    + " — pin it in jk.toml or supply it from a [platform-dependencies] BOM.");
            byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(Dependency.platformManaged(shortName, module));
            return;
        }
        if (versionToken.equals(REFRESH_VERSIONS_PLACEHOLDER)) {
            RefreshVersions.Lookup pin = refreshVersions.lookup(parts[0], parts[1]);
            if (pin.found()) {
                versionToken = Objects.requireNonNull(pin.version());
            } else {
                report.warning(placeholderRow(module, pin));
                byScope.computeIfAbsent(scope, s -> new ArrayList<>())
                        .add(Dependency.platformManaged(shortName, module));
                return;
            }
        }
        VersionSelector selector = VersionSelector.parse(versionToken);
        byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(Dependency.of(shortName, module, selector));
    }

    /** The version refreshVersions writes in a script while the pin lives in {@code versions.properties}. */
    static final String REFRESH_VERSIONS_PLACEHOLDER = "_";

    /** The row for a {@code _} version {@code versions.properties} does not settle; {@code pin} says why. */
    static String placeholderRow(String module, RefreshVersions.Lookup pin) {
        String why = pin.ambiguous()
                ? "versions.properties has both `version." + String.join("` and `version.", pin.keys())
                        + "` and either could be its key"
                : "versions.properties beside the build has no entry for it";
        return "dependency `" + module + "` has the version `_` (refreshVersions keeps the pin in"
                + " versions.properties) and " + why + "; written without a version — pin it in jk.toml.";
    }

    /** {@code y `a`} or {@code ies `a`, `b`}, finishing the word "propert". */
    private static String plural(List<String> names) {
        List<String> quoted = names.stream().map(n -> "`" + n + "`").toList();
        return (quoted.size() == 1 ? "y " : "ies ") + String.join(", ", quoted);
    }

    /**
     * Reverse-map {@code group:artifact} to a unique short name in the layered library catalog.
     * Empty when zero or multiple catalog names share the GA (never invent a name).
     */
    static Optional<String> shortNameFor(String groupArtifact) {
        if (groupArtifact.isBlank()) return Optional.empty();
        try {
            LibraryCatalog catalog = LibraryCatalog.layered();
            List<String> hits = new ArrayList<>();
            for (String name : catalog.names()) {
                var mod = catalog.lookup(name);
                if (mod.isPresent() && groupArtifact.equals(mod.get().moduleKey())) {
                    hits.add(name);
                    if (hits.size() > 1) return Optional.empty();
                }
            }
            return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve a version-catalog accessor ({@code libs.junit.platform.launcher}, {@code
     * libs.bundles.testing}) against the located catalog and add the coordinate(s). The leading
     * segment is the catalog name. Unresolvable accessors — missing catalog, unknown alias, or a
     * version/plugin accessor that is not a dependency — are reported.
     */
    private void resolveCatalogAccessor(String accessor, Scope scope) {
        if (catalog == null) {
            report.error("dependency `" + accessor + "` references a Gradle version catalog, but no"
                    + " gradle/libs.versions.toml was found (searched the project dir and its parent)."
                    + " Declare the coordinate directly in jk.toml.");
            return;
        }
        String rest = accessor.substring(accessor.indexOf('.') + 1);
        if (rest.startsWith("bundles.")) {
            addBundle(accessor, rest.substring("bundles.".length()), scope);
            return;
        }
        if (rest.startsWith("versions.") || rest.startsWith("plugins.")) {
            report.warning("catalog accessor `" + accessor + "` refers to a version/plugin, not a"
                    + " library; jk import only maps library and bundle accessors. Skipped.");
            return;
        }
        Optional<String> coord = catalog.resolveLibrary(rest);
        if (coord.isEmpty()) {
            report.error("library `" + accessor + "` was not found in the version catalog"
                    + "; dropped. Declare it directly in jk.toml.");
            return;
        }
        addDependency(scope, coord.get());
    }

    private void addBundle(String accessor, String bundle, Scope scope) {
        Optional<GradleVersionCatalog.BundleResolution> resolved =
                Objects.requireNonNull(catalog).resolveBundle(bundle);
        if (resolved.isEmpty()) {
            report.error("bundle `" + accessor + "` was not found in the version catalog; dropped.");
            return;
        }
        GradleVersionCatalog.BundleResolution br = resolved.get();
        for (String missing : br.missingMembers()) {
            report.warning("bundle `" + accessor + "` member `" + missing
                    + "` was not found in [libraries] (or had no resolvable module); skipped.");
        }
        if (br.isEmpty()) {
            report.error("bundle `" + accessor + "` expanded to no libraries; dropped.");
            return;
        }
        for (String coord : br.coordinates()) addDependency(scope, coord);
    }

    /** The jk scope a Gradle configuration means; {@code null} for one jk has no table for. */
    static @Nullable Scope mapConfiguration(String configuration) {
        return switch (configuration) {
            case "implementation", "api", "compile" -> Scope.MAIN;
            case "runtimeOnly", "runtime" -> Scope.RUNTIME;
            // Boot dev-loop configurations map 1:1 to jk's dev scopes.
            case "developmentOnly" -> Scope.DEV;
            case "testAndDevelopmentOnly" -> Scope.TEST_DEV;
            case "compileOnly", "compileOnlyApi", "providedRuntime", "providedCompile" -> Scope.PROVIDED;
            case "testImplementation", "testApi", "testCompile", "testRuntimeOnly", "testRuntime", "testCompileOnly" ->
                Scope.TEST;
            case "annotationProcessor", "kapt", "ksp" -> Scope.PROCESSOR;
            case "testAnnotationProcessor", "kaptTest", "kspTest" -> Scope.TEST_PROCESSOR;
            default -> null;
        };
    }
}
