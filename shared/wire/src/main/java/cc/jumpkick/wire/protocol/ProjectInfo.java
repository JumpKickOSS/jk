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
 * refreshing, and a thin client cannot read a manifest to find out.
 *
 * <p>{@code coordinatorOnly} is a workspace root that carries no sources of its own. It builds as
 * a unit (it runs the workspace's build logic) but compiles, packages and publishes nothing, so
 * {@code jk install} must neither plan a {@code cache-install} for it nor claim it installed one.
 * A workspace root <em>with</em> sources is an ordinary publishing module and reports {@code false}.
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
        String productLib) {

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
                "");
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.PROJECT_INFO_ACK + "\""
                + ",\"error\":" + quoteOrNull(error)
                + ",\"group\":" + Jsonl.quote(group)
                + ",\"name\":" + Jsonl.quote(name)
                + ",\"version\":" + Jsonl.quote(version)
                + ",\"jdk\":" + Jsonl.quote(jdk)
                + ",\"javaRelease\":" + javaRelease
                + ",\"kotlin\":" + kotlin
                + ",\"kotlinVersion\":" + Jsonl.quote(kotlinVersion)
                + ",\"groovy\":" + groovy
                + ",\"groovyVersion\":" + Jsonl.quote(groovyVersion)
                + ",\"layoutSimple\":" + layoutSimple
                + ",\"workspaceRoot\":" + workspaceRoot
                + ",\"workspaceRootDir\":" + Jsonl.quote(workspaceRootDir)
                + ",\"modules\":" + Jsonl.map(zipModules())
                + ",\"application\":" + application
                + ",\"mainClass\":" + Jsonl.quote(mainClass)
                + ",\"assembly\":" + assembly
                + ",\"applicationConfig\":" + Jsonl.quote(applicationConfig)
                + ",\"nativeMode\":" + Jsonl.quote(nativeMode)
                + ",\"graal\":" + Jsonl.quote(graal)
                + ",\"springBoot\":" + springBoot
                + ",\"springBootVersion\":" + Jsonl.quote(springBootVersion)
                + ",\"formatStyle\":" + Jsonl.quote(formatStyle)
                + ",\"formatJava\":" + Jsonl.quote(formatJava)
                + ",\"formatKotlin\":" + Jsonl.quote(formatKotlin)
                + optionalBoolJson("formatOptimizeImports", formatOptimizeImports)
                + optionalBoolJson("formatImportOrder", formatImportOrder)
                + optionalBoolJson("formatRemoveUnusedImports", formatRemoveUnusedImports)
                + ",\"hasLock\":" + hasLock
                + ",\"lockJdk\":" + Jsonl.quote(lockJdk)
                + ",\"mainJarPath\":" + Jsonl.quote(mainJarPath)
                + ",\"assemblyJarPath\":" + Jsonl.quote(assemblyJarPath)
                + ",\"nativeBinPath\":" + Jsonl.quote(nativeBinPath)
                + ",\"nativeLibPath\":" + Jsonl.quote(nativeLibPath)
                + ",\"pathDeps\":" + EngineProtocol.quoteArray(pathDeps)
                + ",\"sourcesJarPath\":" + Jsonl.quote(sourcesJarPath)
                + ",\"javadocJarPath\":" + Jsonl.quote(javadocJarPath)
                + ",\"envRefs\":" + EngineProtocol.quoteArray(envRefs)
                + ",\"sourceCount\":" + sourceCount
                + ",\"testCount\":" + testCount
                + ",\"nativeExplicitlyDisabled\":" + nativeExplicitlyDisabled
                + ",\"classesDir\":" + Jsonl.quote(classesDir)
                + ",\"testClassesDir\":" + Jsonl.quote(testClassesDir)
                + ",\"kotlinClassesDir\":" + Jsonl.quote(kotlinClassesDir)
                + ",\"groovyClassesDir\":" + Jsonl.quote(groovyClassesDir)
                + ",\"testResultsDir\":" + Jsonl.quote(testResultsDir)
                + ",\"testIncludeTags\":" + EngineProtocol.quoteArray(testIncludeTags)
                + ",\"testExcludeTags\":" + EngineProtocol.quoteArray(testExcludeTags)
                + ",\"lockStale\":" + lockStale
                + ",\"scala\":" + scala
                + ",\"scalaVersion\":" + Jsonl.quote(scalaVersion)
                + ",\"coordinatorOnly\":" + coordinatorOnly
                + ",\"productLib\":" + Jsonl.quote(productLib)
                + "}";
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
                orEmpty(Jsonl.str(line, "productLib")));
    }

    /** {@code ,"key":true|false} when set; empty string when unset (tri-state). */
    private static String optionalBoolJson(String key, @Nullable Boolean value) {
        if (value == null) return "";
        return ",\"" + key + "\":" + value;
    }

    /** Present JSON boolean → its value; absent → {@code null}. */
    private static @Nullable Boolean optionalBool(String json, String key) {
        if (!Jsonl.has(json, key)) return null;
        return Jsonl.bool(json, key, false);
    }

    private static String quoteOrNull(@Nullable String s) {
        return s == null ? "null" : Jsonl.quote(s);
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
