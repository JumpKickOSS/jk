// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One-shot parsed-project summary ({@link EngineProtocol#PROJECT_INFO_REQUEST}): flat scalars and
 * string lists. Non-null {@code error} is printable and other fields are defaulted.
 *
 * <p>Format hygiene flags ({@code formatOptimizeImports}, {@code formatImportOrder}, {@code
 * formatRemoveUnusedImports}) are tri-state: {@code null} means the {@code [format]} key was
 * absent (client applies the built-in default); non-null is the explicit toml value.
 *
 * <p>{@code productLib} is {@code [install] product-lib} — the directory under jk's own product
 * library that this module's packaged artifact is materialized into. Empty for every ordinary
 * target. It is on the wire because the CLI owns that destination and must decide whether it needs
 * refreshing, and a thin client cannot read a manifest to find out. {@code productBin} is
 * {@code [install] product-bin}, the PATH client under jk's own {@code bin/} that this module's
 * native binary replaces; on the wire for the same reason.
 *
 * <p>{@code coordinatorOnly} is a workspace root that carries no sources of its own. It builds as
 * a unit (it runs the workspace's build logic) but compiles, packages and publishes nothing, so
 * {@code jk install} must neither plan a {@code cache-install} for it nor claim it installed one.
 * A workspace root <em>with</em> sources is an ordinary publishing module and reports {@code false}.
 *
 * <p>{@code toolchains} is every workspace module's effective toolchain, {@code dir →
 * <resolverSpec>@<javaRelease>} ({@link Toolchain}), with the root's inheritance already applied
 * — a member that names no {@code jdk} of its own carries the root's. It is on the wire so the
 * client's JDK pre-flight learns every member's pin from the one summary it already holds instead
 * of asking for each member in turn. Keyed like {@code modules}, by absolute directory.
 */
public record ProjectInfo(
        @Nullable String error,
        String group,
        String name,
        String version,
        String jdk,
        int javaRelease,
        boolean kotlin,
        String kotlinVersion,
        boolean groovy,
        String groovyVersion,
        boolean layoutSimple,
        boolean workspaceRoot,
        String workspaceRootDir,
        List<String> moduleDirs,
        boolean application,
        String mainClass,
        boolean assembly,
        String applicationConfig,
        String nativeMode,
        String graal,
        boolean springBoot,
        String springBootVersion,
        String formatStyle,
        String formatJava,
        String formatKotlin,
        @Nullable Boolean formatOptimizeImports,
        @Nullable Boolean formatImportOrder,
        @Nullable Boolean formatRemoveUnusedImports,
        boolean hasLock,
        String lockJdk,
        String mainJarPath,
        String assemblyJarPath,
        String nativeBinPath,
        String nativeLibPath,
        List<String> pathDeps,
        String sourcesJarPath,
        String javadocJarPath,
        List<String> envRefs,
        List<String> moduleNames,
        int sourceCount,
        int testCount,
        boolean nativeExplicitlyDisabled,
        String classesDir,
        String testClassesDir,
        String kotlinClassesDir,
        String groovyClassesDir,
        String testResultsDir,
        List<String> testIncludeTags,
        List<String> testExcludeTags,
        boolean lockStale,
        boolean scala,
        String scalaVersion,
        boolean coordinatorOnly,
        String productLib,
        String productBin,
        Map<String, String> toolchains) {

    /**
     * One module's effective toolchain as {@link ProjectInfo#toolchains} carries it: the resolver
     * spec the engine resolves ({@code temurin-21}; empty when the module pins no vendor or
     * version) and the {@code java} level it compiles for.
     */
    public record Toolchain(String jdk, int javaRelease) {

        /** {@code <jdk>@<javaRelease>}. */
        public String encode() {
            return jdk + "@" + javaRelease;
        }

        /** The inverse of {@link #encode}; a level that does not parse reads as 0, as an unset one does. */
        public static Toolchain decode(String encoded) {
            int at = encoded.lastIndexOf('@');
            if (at < 0) return new Toolchain(encoded, 0);
            int java;
            try {
                java = Integer.parseInt(encoded.substring(at + 1));
            } catch (NumberFormatException unparsed) {
                java = 0;
            }
            return new Toolchain(encoded.substring(0, at), java);
        }
    }

    /** {@link #toolchains} decoded, in the engine's module order. */
    public Map<String, Toolchain> moduleToolchains() {
        var out = new LinkedHashMap<String, Toolchain>();
        for (var e : toolchains.entrySet()) out.put(e.getKey(), Toolchain.decode(e.getValue()));
        return out;
    }

    /** The {@code group:name} display coordinate. */
    public String coord() {
        return group + ":" + name;
    }

    public static ProjectInfo error(@Nullable String message) {
        return new ProjectInfo(
                message == null ? "" : message,
                "",
                "",
                "",
                "",
                0,
                false,
                "",
                false,
                "",
                true,
                false,
                "",
                List.of(),
                false,
                "",
                false,
                "",
                "DISABLED",
                "",
                false,
                "",
                "",
                "",
                "",
                null,
                null,
                null,
                false,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                "",
                "",
                List.of(),
                List.of(),
                0,
                0,
                false,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                false,
                false,
                "",
                false,
                "",
                "",
                Map.of());
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.PROJECT_INFO_ACK)
                .string("error", error)
                .string("group", group)
                .string("name", name)
                .string("version", version)
                .string("jdk", jdk)
                .number("javaRelease", javaRelease)
                .bool("kotlin", kotlin)
                .string("kotlinVersion", kotlinVersion)
                .bool("groovy", groovy)
                .string("groovyVersion", groovyVersion)
                .bool("layoutSimple", layoutSimple)
                .bool("workspaceRoot", workspaceRoot)
                .string("workspaceRootDir", workspaceRootDir)
                .map("modules", zipModules())
                .bool("application", application)
                .string("mainClass", mainClass)
                .bool("assembly", assembly)
                .string("applicationConfig", applicationConfig)
                .string("nativeMode", nativeMode)
                .string("graal", graal)
                .bool("springBoot", springBoot)
                .string("springBootVersion", springBootVersion)
                .string("formatStyle", formatStyle)
                .string("formatJava", formatJava)
                .string("formatKotlin", formatKotlin)
                .optionalBool("formatOptimizeImports", formatOptimizeImports)
                .optionalBool("formatImportOrder", formatImportOrder)
                .optionalBool("formatRemoveUnusedImports", formatRemoveUnusedImports)
                .bool("hasLock", hasLock)
                .string("lockJdk", lockJdk)
                .string("mainJarPath", mainJarPath)
                .string("assemblyJarPath", assemblyJarPath)
                .string("nativeBinPath", nativeBinPath)
                .string("nativeLibPath", nativeLibPath)
                .array("pathDeps", pathDeps)
                .string("sourcesJarPath", sourcesJarPath)
                .string("javadocJarPath", javadocJarPath)
                .array("envRefs", envRefs)
                .number("sourceCount", sourceCount)
                .number("testCount", testCount)
                .bool("nativeExplicitlyDisabled", nativeExplicitlyDisabled)
                .string("classesDir", classesDir)
                .string("testClassesDir", testClassesDir)
                .string("kotlinClassesDir", kotlinClassesDir)
                .string("groovyClassesDir", groovyClassesDir)
                .string("testResultsDir", testResultsDir)
                .array("testIncludeTags", testIncludeTags)
                .array("testExcludeTags", testExcludeTags)
                .bool("lockStale", lockStale)
                .bool("scala", scala)
                .string("scalaVersion", scalaVersion)
                .bool("coordinatorOnly", coordinatorOnly)
                .string("productLib", productLib)
                .string("productBin", productBin)
                .map("toolchains", toolchains)
                .finish();
    }

    public static ProjectInfo decode(String line) {
        String error = Jsonl.str(line, "error");
        return new ProjectInfo(
                error,
                orEmpty(Jsonl.str(line, "group")),
                orEmpty(Jsonl.str(line, "name")),
                orEmpty(Jsonl.str(line, "version")),
                orEmpty(Jsonl.str(line, "jdk")),
                Jsonl.intValue(line, "javaRelease", 0),
                Jsonl.bool(line, "kotlin", false),
                orEmpty(Jsonl.str(line, "kotlinVersion")),
                Jsonl.bool(line, "groovy", false),
                orEmpty(Jsonl.str(line, "groovyVersion")),
                Jsonl.bool(line, "layoutSimple", true),
                Jsonl.bool(line, "workspaceRoot", false),
                orEmpty(Jsonl.str(line, "workspaceRootDir")),
                List.copyOf(Jsonl.strMap(line, "modules").keySet()),
                Jsonl.bool(line, "application", false),
                orEmpty(Jsonl.str(line, "mainClass")),
                Jsonl.bool(line, "assembly", false),
                orEmpty(Jsonl.str(line, "applicationConfig")),
                orEmpty(Jsonl.str(line, "nativeMode")),
                orEmpty(Jsonl.str(line, "graal")),
                Jsonl.bool(line, "springBoot", false),
                orEmpty(Jsonl.str(line, "springBootVersion")),
                orEmpty(Jsonl.str(line, "formatStyle")),
                orEmpty(Jsonl.str(line, "formatJava")),
                orEmpty(Jsonl.str(line, "formatKotlin")),
                optionalBool(line, "formatOptimizeImports"),
                optionalBool(line, "formatImportOrder"),
                optionalBool(line, "formatRemoveUnusedImports"),
                Jsonl.bool(line, "hasLock", false),
                orEmpty(Jsonl.str(line, "lockJdk")),
                orEmpty(Jsonl.str(line, "mainJarPath")),
                orEmpty(Jsonl.str(line, "assemblyJarPath")),
                orEmpty(Jsonl.str(line, "nativeBinPath")),
                orEmpty(Jsonl.str(line, "nativeLibPath")),
                Jsonl.strArray(line, "pathDeps"),
                orEmpty(Jsonl.str(line, "sourcesJarPath")),
                orEmpty(Jsonl.str(line, "javadocJarPath")),
                Jsonl.strArray(line, "envRefs"),
                List.copyOf(Jsonl.strMap(line, "modules").values()),
                Jsonl.intValue(line, "sourceCount", 0),
                Jsonl.intValue(line, "testCount", 0),
                Jsonl.bool(line, "nativeExplicitlyDisabled", false),
                orEmpty(Jsonl.str(line, "classesDir")),
                orEmpty(Jsonl.str(line, "testClassesDir")),
                orEmpty(Jsonl.str(line, "kotlinClassesDir")),
                orEmpty(Jsonl.str(line, "groovyClassesDir")),
                orEmpty(Jsonl.str(line, "testResultsDir")),
                Jsonl.strArray(line, "testIncludeTags"),
                Jsonl.strArray(line, "testExcludeTags"),
                Jsonl.bool(line, "lockStale", false),
                Jsonl.bool(line, "scala", false),
                orEmpty(Jsonl.str(line, "scalaVersion")),
                Jsonl.bool(line, "coordinatorOnly", false),
                orEmpty(Jsonl.str(line, "productLib")),
                orEmpty(Jsonl.str(line, "productBin")),
                Jsonl.strMap(line, "toolchains"));
    }

    /** {@code ,"key":true|false} when set; empty string when unset (tri-state). */
    /** Present JSON boolean → its value; absent → {@code null}. */
    private static @Nullable Boolean optionalBool(String json, String key) {
        if (!Jsonl.has(json, key)) return null;
        return Jsonl.bool(json, key, false);
    }

    /**
     * The ONE wire encoding for the module set: an ordered {@code dir → name} object
     * ({@code Jsonl.strMap}), so dirs and names cannot misalign on decode — the old parallel
     * {@code moduleDirs}/{@code moduleNames} arrays forced every consumer to defend with
     * size-min clamps.
     */
    private Map<String, String> zipModules() {
        var out = new LinkedHashMap<String, String>();
        for (int i = 0; i < moduleDirs.size(); i++) {
            String name = i < moduleNames.size() ? moduleNames.get(i) : "";
            out.put(moduleDirs.get(i), name);
        }
        return out;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
