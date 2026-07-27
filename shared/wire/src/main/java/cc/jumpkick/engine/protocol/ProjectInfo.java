// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * One-shot parsed-project summary ({@link EngineProtocol#PROJECT_INFO_REQUEST}): flat scalars and
 * string lists. Non-null {@code error} is printable and other fields are defaulted.
 */
public record ProjectInfo(
        String error,
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
        String nativeMode,
        String graal,
        boolean springBoot,
        String springBootVersion,
        String formatStyle,
        String formatJava,
        String formatKotlin,
        boolean formatOptimizeImports,
        boolean hasLock,
        String lockJdk,
        String mainJarPath,
        String assemblyJarPath,
        String nativeBinPath,
        String nativeLibPath,
        List<String> pathDeps,
        String sourcesJarPath,
        String javadocJarPath,
        List<String> envRefs) {

    /** The {@code group:name} display coordinate. */
    public String coord() {
        return group + ":" + name;
    }

    public static ProjectInfo error(String message) {
        return new ProjectInfo(
                message,
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
                "DISABLED",
                "",
                false,
                "",
                "",
                "",
                "",
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                "",
                "",
                List.of());
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
                + ",\"moduleDirs\":" + EngineProtocol.quoteArray(moduleDirs)
                + ",\"application\":" + application
                + ",\"mainClass\":" + Jsonl.quote(mainClass)
                + ",\"assembly\":" + assembly
                + ",\"nativeMode\":" + Jsonl.quote(nativeMode)
                + ",\"graal\":" + Jsonl.quote(graal)
                + ",\"springBoot\":" + springBoot
                + ",\"springBootVersion\":" + Jsonl.quote(springBootVersion)
                + ",\"formatStyle\":" + Jsonl.quote(formatStyle)
                + ",\"formatJava\":" + Jsonl.quote(formatJava)
                + ",\"formatKotlin\":" + Jsonl.quote(formatKotlin)
                + ",\"formatOptimizeImports\":" + formatOptimizeImports
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
                Jsonl.strArray(line, "moduleDirs"),
                Jsonl.bool(line, "application", false),
                orEmpty(Jsonl.str(line, "mainClass")),
                Jsonl.bool(line, "assembly", false),
                orEmpty(Jsonl.str(line, "nativeMode")),
                orEmpty(Jsonl.str(line, "graal")),
                Jsonl.bool(line, "springBoot", false),
                orEmpty(Jsonl.str(line, "springBootVersion")),
                orEmpty(Jsonl.str(line, "formatStyle")),
                orEmpty(Jsonl.str(line, "formatJava")),
                orEmpty(Jsonl.str(line, "formatKotlin")),
                Jsonl.bool(line, "formatOptimizeImports", false),
                Jsonl.bool(line, "hasLock", false),
                orEmpty(Jsonl.str(line, "lockJdk")),
                orEmpty(Jsonl.str(line, "mainJarPath")),
                orEmpty(Jsonl.str(line, "assemblyJarPath")),
                orEmpty(Jsonl.str(line, "nativeBinPath")),
                orEmpty(Jsonl.str(line, "nativeLibPath")),
                Jsonl.strArray(line, "pathDeps"),
                orEmpty(Jsonl.str(line, "sourcesJarPath")),
                orEmpty(Jsonl.str(line, "javadocJarPath")),
                Jsonl.strArray(line, "envRefs"));
    }

    private static String quoteOrNull(String s) {
        return s == null ? "null" : Jsonl.quote(s);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
