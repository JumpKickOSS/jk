// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import static cc.jumpkick.guard.schema.KeySpec.choice;
import static cc.jumpkick.guard.schema.KeySpec.optional;
import static cc.jumpkick.guard.schema.KeySpec.required;
import static cc.jumpkick.guard.schema.KeyType.BOOL;
import static cc.jumpkick.guard.schema.KeyType.INT;
import static cc.jumpkick.guard.schema.KeyType.NUMBER;
import static cc.jumpkick.guard.schema.KeyType.NUMBER_OR_TABLE;
import static cc.jumpkick.guard.schema.KeyType.STRING;
import static cc.jumpkick.guard.schema.KeyType.STRING_LIST;
import static cc.jumpkick.guard.schema.KeyType.STRING_OR_LIST;
import static cc.jumpkick.guard.schema.KeyType.TABLE;
import static cc.jumpkick.guard.schema.KeyType.TABLE_LIST;

import cc.jumpkick.guard.schema.Kind.InsteadRule;
import cc.jumpkick.guard.schema.Kind.KeyGroup;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.RepositorySpec;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The key tables, one-of groups and examples per kind. Data, not code: the loader validates against
 * it, {@code explain --schema} prints it, the manual renders it, and a test loads every example
 * through the real loader so the reference cannot rot.
 */
final class KindSchemas {

    private KindSchemas() {}

    /** Keys every kind accepts. {@code kind} and {@code why} are required on all of them. */
    static final List<KeySpec> COMMON = List.of(
            choice("kind", true, "the closed rule kind", kindIds()),
            required("why", STRING, "one sentence: the defect this rule prevents"),
            optional("scope", STRING_OR_LIST, "module globs the rule applies to; default every module"),
            choice("source-set", false, "which compiled sources a bytecode rule reads", "main", "test", "guard", "all"),
            optional("allow", TABLE_LIST, "{ in, reason } pairs where the rule does not apply; a stale entry is red"),
            optional("baseline", BOOL, "tolerate today's violations in jk-guards-baseline.toml; tighten-only"),
            optional("fixture", STRING, "guard-fixtures/<id> with Bad and Ok sources that prove the rule bites"),
            optional("locked", BOOL, "a pack's word: a consumer cannot allow against this rule"));

    static final List<String> BLANK_MODES = List.of("comments", "comments+strings", "none", "code");

    private static final Map<Kind, List<KeySpec>> KEYS = new EnumMap<>(Kind.class);
    private static final Map<Kind, List<KeyGroup>> GROUPS = new EnumMap<>(Kind.class);
    private static final Map<Kind, InsteadRule> INSTEAD = new EnumMap<>(Kind.class);
    private static final Map<Kind, String> EXAMPLES = new EnumMap<>(Kind.class);

    static {
        define(
                Kind.FORBID,
                InsteadRule.REQUIRED,
                List.of(
                        required(
                                "signatures",
                                STRING_LIST,
                                "pkg.Class, pkg.Class#method(desc), #FIELD, #<init>(**), pkg.**, or a @bundled set"),
                        optional(
                                "owner",
                                STRING_OR_LIST,
                                "the class or package where the call is legal; probed every run"),
                        optional("args", STRING_LIST, "fire only when this literal immediately precedes the invoke"),
                        optional(
                                "except-annotated",
                                STRING_OR_LIST,
                                "origin members carrying this annotation are exempt")),
                List.of(),
                """
                [guards.no-system-out]
                kind       = "forbid"
                signatures = ["@jdk-system-out"]
                instead    = "the injected org.slf4j.Logger"
                why        = "stdout is not a log sink in a service"
                """);
        define(
                Kind.ANNOTATE,
                InsteadRule.OPTIONAL,
                List.of(
                        optional("require", STRING, "annotation that must be present"),
                        optional("forbid", STRING, "annotation that must not be present"),
                        choice(
                                "on",
                                true,
                                "the element the rule reads",
                                "package",
                                "class",
                                "method",
                                "field",
                                "parameter",
                                "test-class"),
                        optional("matching", TABLE, "a classes-style predicate narrowing the elements"),
                        optional("with-value", STRING, "an attribute value the annotation must carry")),
                List.of(new KeyGroup(List.of("require", "forbid"), true)),
                """
                [guards.no-field-injection]
                kind    = "annotate"
                forbid  = "org.springframework.beans.factory.annotation.Autowired"
                on      = "field"
                instead = "constructor injection"
                why     = "a field-injected bean cannot be constructed in a test without the container"
                """);
        define(
                Kind.CLASSES,
                InsteadRule.OPTIONAL,
                List.of(
                        required("that", TABLE, "predicates selecting classes; each negatable with a leading !"),
                        required("should", TABLE, "predicates every selected class must satisfy")),
                List.of(),
                """
                [guards.entities-live-in-model]
                kind   = "classes"
                that   = { annotated-with = "jakarta.persistence.Entity" }
                should = { reside-in = "..domain.model..", be = ["!abstract"] }
                why    = "an entity outside the model package is a repository leak"
                """);
        define(
                Kind.LAYERS,
                InsteadRule.ABSENT,
                List.of(
                        required("layers", TABLE, "name = package glob, module glob, or a list of module globs"),
                        required("access", TABLE, "name = [layers it may depend on]"),
                        choice("edges", false, "which dependency edges are read", "manifest", "classes", "both"),
                        optional("exports", TABLE, "module = [packages visible across modules]"),
                        optional("exact", BOOL, "a declared but unused module dependency is a violation"),
                        optional(
                                "closed",
                                BOOL,
                                "an edge from a layered module to a module in no layer is a violation")),
                List.of(),
                """
                [guards.layers]
                kind   = "layers"
                layers = { web = "..web..", service = "..service..", repo = "..repository.." }
                access = { web = ["service"], service = ["repo"] }
                edges  = "classes"
                why    = "controllers that touch repositories skip validation"
                """);
        define(
                Kind.CYCLES,
                InsteadRule.ABSENT,
                List.of(
                        optional("matching", STRING, "slice pattern, e.g. com.acme.features.(*).."),
                        choice("over", false, "cycles over modules instead of packages", "modules"),
                        optional("across-modules", BOOL, "follow package edges across module boundaries")),
                List.of(new KeyGroup(List.of("matching", "over"), true)),
                """
                [guards.no-feature-cycles]
                kind     = "cycles"
                matching = "com.acme.features.(*).."
                why      = "a cyclic component has no edge anyone can name to cut"
                """);
        define(Kind.SPLIT_PACKAGE, InsteadRule.ABSENT, List.of(), List.of(), """
                [guards.one-module-per-package]
                kind = "split-package"
                why  = "package-private reach across a jar boundary stops working under JPMS"
                """);
        define(
                Kind.API,
                InsteadRule.ABSENT,
                List.of(
                        required("against", STRING, "a jar path, a g:a:v the lock pins, previous-release, or baseline"),
                        choice("breaking", false, "what a breaking change does", "forbid", "baseline"),
                        optional("packages", STRING_LIST, "API package globs; default the module's exported packages"),
                        optional("codes", STRING_LIST, "japicmp change codes to ignore")),
                List.of(),
                """
                [guards.api-compat]
                kind     = "api"
                against  = "previous-release"
                packages = ["com.acme.sdk.."]
                breaking = "baseline"
                why      = "a removed public method is a customer's build going red"
                """);
        define(Kind.TEST, InsteadRule.OPTIONAL, List.of(), List.of(), """
                # not a TOML kind: a guard test is a @Guard method in a @GuardSuite class under src/guard/java.
                # jk guard explain --schema guard-test prints the skeleton; the id shares the rule namespace.
                """);
        define(
                Kind.DEPEND,
                InsteadRule.OPTIONAL,
                List.of(
                        optional("ban", STRING_LIST, "coordinates (g:a, * in artifact) that may not appear"),
                        optional("coordinate", STRING, "one coordinate whose scope placement is constrained"),
                        optional("only-in", STRING_LIST, "scopes the coordinate may appear in"),
                        optional("never-in", STRING_LIST, "scopes the coordinate may not appear in"),
                        optional("require", TABLE, "g:a = version floor"),
                        optional("licenses", TABLE, "forbid = [SPDX globs]"),
                        optional("scopes", STRING_LIST, "scopes the rule reads; default all"),
                        optional("convergence", BOOL, "one version per artifact in the lock"),
                        optional("no-dynamic", BOOL, "no floating selectors"),
                        optional("no-snapshot", BOOL, "no -SNAPSHOT versions")),
                List.of(new KeyGroup(
                        List.of("ban", "coordinate", "require", "licenses", "convergence", "no-dynamic", "no-snapshot"),
                        false)),
                """
                [guards.no-junit4]
                kind    = "depend"
                ban     = ["junit:junit", "commons-logging:commons-logging"]
                instead = "org.junit.jupiter:junit-jupiter · org.slf4j:jcl-over-slf4j"
                why     = "one test framework; one logging facade"
                """);
        define(
                Kind.TOOLCHAIN,
                InsteadRule.ABSENT,
                List.of(
                        optional("java", STRING, "JDK release range, e.g. >=21"),
                        optional("kotlin", STRING, "Kotlin version range"),
                        optional("plugins", TABLE, "pinned = true: no floating plugin selector"),
                        optional("repositories", TABLE, "only = [repository names allowed]")),
                List.of(new KeyGroup(List.of("java", "kotlin", "plugins", "repositories"), false)),
                """
                [guards.toolchain]
                kind         = "toolchain"
                java         = ">=21"
                repositories = { only = ["%s", "corp-nexus"] }
                why          = "one JDK floor; no artifact from an unvetted repository"
                """.formatted(RepositorySpec.CENTRAL));
        define(
                Kind.TIERS,
                InsteadRule.OPTIONAL,
                List.of(
                        optional("uses", STRING_LIST, "type globs whose use routes a test class"),
                        optional("tagged", STRING_LIST, "tags whose presence routes a test class"),
                        optional("suite", STRING, "the suite such classes must live in"),
                        optional("tag", STRING, "the tag such classes must carry")),
                List.of(new KeyGroup(List.of("uses", "tagged"), true), new KeyGroup(List.of("suite", "tag"), true)),
                """
                [guards.containers-are-integration]
                kind  = "tiers"
                uses  = ["org.testcontainers.**"]
                suite = "integration"
                why   = "a Docker daemon is not a unit-test dependency"
                """);
        define(
                Kind.TEXT,
                InsteadRule.REQUIRED,
                List.of(
                        optional("pattern", STRING, "a Java regex"),
                        optional("patterns", STRING_LIST, "several regexes, any of which is a hit"),
                        optional(
                                "hit",
                                STRING,
                                "a snippet the pattern must match through the chosen view — the bite proof"),
                        optional("miss", STRING, "a snippet the pattern must not match"),
                        optional("files", STRING_LIST, "file globs; default the module source trees"),
                        new KeySpec(
                                "blank",
                                STRING,
                                false,
                                BLANK_MODES,
                                "the view: comments blanked (default), comments+strings, none, or code (comments only)"),
                        optional("count", TABLE, "{ exactly | min | max, per = file | tree | match }"),
                        optional("owner", STRING_OR_LIST, "files where a match is legal"),
                        optional("languages", STRING_LIST, "restrict to these source languages")),
                List.of(new KeyGroup(List.of("pattern", "patterns"), true)),
                """
                [guards.no-todo-without-owner]
                kind    = "text"
                pattern = "\\\\bTODO\\\\b(?!\\\\()"
                blank   = "code"
                hit     = "// TODO fix later"
                miss    = "// TODO(bsant) fix later"
                instead = "TODO(<owner>) …"
                why     = "an unowned TODO is a comment nobody will read again"
                """);
        define(
                Kind.METRIC,
                InsteadRule.ABSENT,
                List.of(
                        required(
                                "measure",
                                STRING,
                                "lines, fqcn, matches:<rule>, comment-lines, methods, params, public-members, cyclomatic, coverage.line, coverage.branch, jar-size, native-size"),
                        optional("cap", NUMBER_OR_TABLE, "maximum, scalar or per-language table"),
                        optional("min", NUMBER_OR_TABLE, "minimum, scalar or per-language table"),
                        optional(
                                "band",
                                NUMBER,
                                "with baseline: how far a unit may move either side of its entry and still hold it (default 0)"),
                        choice("per", false, "the unit measured", "file", "class", "method", "module", "comment"),
                        optional("files", STRING_LIST, "file globs for text measures")),
                List.of(new KeyGroup(List.of("cap", "min"), true)),
                """
                [guards.file-size]
                kind     = "metric"
                measure  = "lines"
                cap      = { java = 800, kt = 600 }
                baseline = true
                why      = "a file that no longer fits a context window no longer fits a reviewer"
                """);
        define(
                Kind.VOCABULARY,
                InsteadRule.REQUIRED,
                List.of(
                        required("owner", STRING, "the class whose static final Strings are the vocabulary"),
                        optional("shape", STRING, "exact (default), hyphenated, or regex:<pattern>"),
                        optional("homonyms", STRING_LIST, "literals of the shape that are legitimately something else"),
                        optional("min-length", INT, "ignore shorter literals"),
                        optional("inverse", BOOL, "also report literals of the shape with no owner")),
                List.of(),
                """
                [guards.task-names]
                kind    = "vocabulary"
                owner   = "cc.jumpkick.task.TaskNames"
                shape   = "hyphenated"
                inverse = true
                instead = "TaskNames.<CONSTANT>"
                why     = "a step name typed twice is a rename that half-lands"
                """);
        define(
                Kind.PARITY,
                InsteadRule.ABSENT,
                List.of(
                        required(
                                "left",
                                TABLE,
                                "one extractor: { workspace-modules = \"" + ManifestPaths.MANIFEST + "\" }"),
                        required("right", TABLE, "the other extractor"),
                        choice("direction", false, "which differences are violations", "both", "left-in-right")),
                List.of(),
                """
                [guards.both-builds-see-modules]
                kind  = "parity"
                left  = { workspace-modules = "%s" }
                right = { gradle-includes = "settings.gradle.kts" }
                why   = "a module one build cannot see is built half the time"
                """.formatted(ManifestPaths.MANIFEST));
        define(
                Kind.GENERATED,
                InsteadRule.ABSENT,
                List.of(
                        required("source", TABLE, "the extractor that is the truth"),
                        required("template", TABLE, "table(columns), list, arrow-chain, toml-array, code-block"),
                        required("into", STRING, "the file carrying the generated block"),
                        optional("markers", STRING, "the marker name; default the rule id")),
                List.of(),
                """
                [guards.guard-registry-doc]
                kind     = "generated"
                source   = { guard-ids = true }
                template = { table = ["id", "kind", "why"] }
                into     = "docs/contributors/code-as-art.md"
                markers  = "guards"
                why      = "the registry drifted twice by hand; the third reconciliation is not by hand"
                """);
        define(
                Kind.OUTPUT,
                InsteadRule.ABSENT,
                List.of(
                        optional("pom", TABLE, "no-unspecified = true, groups = [published groups]"),
                        optional("jar", TABLE, "forbid-entries, require-entries"),
                        optional("manifest", TABLE, "jar manifest attributes that must be present")),
                List.of(new KeyGroup(List.of("pom", "jar", "manifest"), false)),
                """
                [guards.published-poms]
                kind = "output"
                pom  = { no-unspecified = true, groups = ["cc.jumpkick"] }
                why  = "a POM with an unspecified coordinate is an artifact nobody can depend on"
                """);
        define(
                Kind.COMMIT,
                InsteadRule.REQUIRED,
                List.of(
                        optional("pattern", STRING, "a regex the message must not match"),
                        optional("patterns", STRING_LIST, "several such regexes"),
                        optional("forbid-trailers", STRING_LIST, "trailer globs, e.g. Co-Authored-By: *bot*"),
                        optional("require", STRING_LIST, "regexes the message must match")),
                List.of(new KeyGroup(List.of("pattern", "patterns", "forbid-trailers", "require"), false)),
                """
                [guards.no-agent-trailers]
                kind            = "commit"
                forbid-trailers = ["Co-Authored-By: *bot*", "Generated-By: *"]
                instead         = "author commits as a human contributor"
                why             = "attribution trailers are noise in blame"
                """);
    }

    private static void define(
            Kind kind, InsteadRule instead, List<KeySpec> keys, List<KeyGroup> groups, String example) {
        KEYS.put(kind, List.copyOf(keys));
        GROUPS.put(kind, List.copyOf(groups));
        INSTEAD.put(kind, instead);
        EXAMPLES.put(kind, example.stripIndent());
    }

    /** The keys every kind shares. */
    public static List<KeySpec> common() {
        return COMMON;
    }

    static List<KeySpec> keysOf(Kind kind) {
        return Objects.requireNonNull(KEYS.get(kind), kind.id());
    }

    static List<KeyGroup> groupsOf(Kind kind) {
        return Objects.requireNonNull(GROUPS.get(kind), kind.id());
    }

    static InsteadRule insteadOf(Kind kind) {
        return Objects.requireNonNull(INSTEAD.get(kind), kind.id());
    }

    static String exampleOf(Kind kind) {
        return Objects.requireNonNull(EXAMPLES.get(kind), kind.id());
    }

    private static String[] kindIds() {
        Kind[] all = Kind.values();
        String[] ids = new String[all.length];
        for (int i = 0; i < all.length; i++) ids[i] = all[i].id();
        return ids;
    }
}
