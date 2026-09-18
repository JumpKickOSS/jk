// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The engine's IDE-agnostic workspace model ({@link EngineProtocol#IDE_MODEL_REQUEST}): the model
 * math needs the parsed root, every workspace module and their lockfiles, so it runs engine-side.
 * The IntelliJ / VS Code generators ({@code cc.jumpkick.ide}, linked by the CLI and the engine
 * alike) consume this instead of {@code JkBuild}.
 *
 * <p>Per-module data rides as parallel lists indexed by {@code moduleDirs}; cross-module edges and
 * per-module lists ride as {@code moduleIndex|…} strings (the {@code WhyReport} convention). Jar
 * and JDK paths are absolute. {@code languages} is each module's comma-joined language set
 * ({@code java,scala}); {@code scalaVersions} its Scala compiler version, {@code ""} for a module
 * that compiles no Scala; {@code scalaJars} the {@code i|path} rows of the Scala compiler closure
 * the store holds for it, empty until a build fetched one. {@code error} non-null means the model
 * could not be computed; its message is ready to print.
 */
public record IdeWireModel(
        @Nullable String error,
        String wsRoot,
        String rootName,
        boolean workspace,
        List<String> moduleDirs,
        List<String> names,
        List<String> javaReleases,
        List<String> mainClasses,
        List<String> classesDirs,
        List<String> testClassesDirs,
        List<String> jdtClassesDirs,
        List<String> jdtTestClassesDirs,
        List<String> genSrcDirs,
        List<String> genTestSrcDirs,
        List<String> libNames,
        List<String> libFiles,
        List<String> libJars,
        List<String> libSources,
        List<String> siblingRefs,
        List<String> libEntries,
        List<String> processorJars,
        List<String> sdkStableNames,
        List<String> sdkNames,
        List<String> sdkLevels,
        List<String> sdkHomes,
        List<String> sdkVersions,
        String defSdkStableName,
        String defSdkName,
        int defSdkLevel,
        String defSdkHome,
        String defSdkVersion,
        List<String> sdkEntries,
        List<String> languages,
        List<String> scalaVersions,
        List<String> scalaJars) {

    /** Sibling-ref scope: on the main (and thus test) classpath. */
    public static final String SCOPE_COMPILE = "COMPILE";

    /** Sibling-ref scope: on the test classpath only. */
    public static final String SCOPE_TEST = "TEST";

    /**
     * Sibling-ref scope: {@code kind = "tests"} edge (Mill testModuleDeps / Maven test-jar) — the
     * sibling module ref is test-scoped AND its test classes join the test classpath.
     */
    public static final String SCOPE_TEST_KIND = "TEST_KIND";

    /**
     * Sibling-ref scope: the sibling is both a main dep and a tests-kind dep. One compile-scoped
     * module ref, plus the sibling's test classes on the test classpath — generators must never
     * emit a second module entry for the same sibling (Eclipse JDT rejects duplicates).
     */
    public static final String SCOPE_COMPILE_TEST_KIND = "COMPILE+TEST_KIND";

    public static IdeWireModel error(String message) {
        return new IdeWireModel(
                message, "", "", false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", 0, "", "", List.of(),
                List.of(), List.of(), List.of());
    }

    /** The languages module {@code i} compiles, in the order {@code java, kotlin, groovy, scala}. */
    public List<String> languagesOf(int i) {
        if (languages == null
                || i < 0
                || i >= languages.size()
                || languages.get(i).isBlank()) return List.of();
        return List.of(languages.get(i).split(","));
    }

    /** Module {@code i}'s Scala compiler version, or {@code null} when it compiles no Scala. */
    public @Nullable String scalaVersionOf(int i) {
        if (scalaVersions == null || i < 0 || i >= scalaVersions.size()) return null;
        String v = scalaVersions.get(i);
        return v == null || v.isBlank() ? null : v;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.IDE_MODEL_ACK)
                .string("error", error)
                .string("wsRoot", wsRoot)
                .string("rootName", rootName)
                .bool("workspace", workspace)
                .array("moduleDirs", moduleDirs)
                .array("names", names)
                .array("javaReleases", javaReleases)
                .array("mainClasses", mainClasses)
                .array("classesDirs", classesDirs)
                .array("testClassesDirs", testClassesDirs)
                .array("jdtClassesDirs", jdtClassesDirs)
                .array("jdtTestClassesDirs", jdtTestClassesDirs)
                .array("genSrcDirs", genSrcDirs)
                .array("genTestSrcDirs", genTestSrcDirs)
                .array("libNames", libNames)
                .array("libFiles", libFiles)
                .array("libJars", libJars)
                .array("libSources", libSources)
                .array("siblingRefs", siblingRefs)
                .array("libEntries", libEntries)
                .array("processorJars", processorJars)
                .array("sdkStableNames", sdkStableNames)
                .array("sdkNames", sdkNames)
                .array("sdkLevels", sdkLevels)
                .array("sdkHomes", sdkHomes)
                .array("sdkVersions", sdkVersions)
                .string("defSdkStableName", defSdkStableName)
                .string("defSdkName", defSdkName)
                .number("defSdkLevel", defSdkLevel)
                .string("defSdkHome", defSdkHome)
                .string("defSdkVersion", defSdkVersion)
                .array("sdkEntries", sdkEntries)
                .array("languages", languages)
                .array("scalaVersions", scalaVersions)
                .array("scalaJars", scalaJars)
                .finish();
    }

    public static IdeWireModel decode(String line) {
        return new IdeWireModel(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "wsRoot")),
                orEmpty(Jsonl.str(line, "rootName")),
                Jsonl.bool(line, "workspace", false),
                Jsonl.strArray(line, "moduleDirs"),
                Jsonl.strArray(line, "names"),
                Jsonl.strArray(line, "javaReleases"),
                Jsonl.strArray(line, "mainClasses"),
                Jsonl.strArray(line, "classesDirs"),
                Jsonl.strArray(line, "testClassesDirs"),
                Jsonl.strArray(line, "jdtClassesDirs"),
                Jsonl.strArray(line, "jdtTestClassesDirs"),
                Jsonl.strArray(line, "genSrcDirs"),
                Jsonl.strArray(line, "genTestSrcDirs"),
                Jsonl.strArray(line, "libNames"),
                Jsonl.strArray(line, "libFiles"),
                Jsonl.strArray(line, "libJars"),
                Jsonl.strArray(line, "libSources"),
                Jsonl.strArray(line, "siblingRefs"),
                Jsonl.strArray(line, "libEntries"),
                Jsonl.strArray(line, "processorJars"),
                Jsonl.strArray(line, "sdkStableNames"),
                Jsonl.strArray(line, "sdkNames"),
                Jsonl.strArray(line, "sdkLevels"),
                Jsonl.strArray(line, "sdkHomes"),
                Jsonl.strArray(line, "sdkVersions"),
                orEmpty(Jsonl.str(line, "defSdkStableName")),
                orEmpty(Jsonl.str(line, "defSdkName")),
                Jsonl.intValue(line, "defSdkLevel", 0),
                orEmpty(Jsonl.str(line, "defSdkHome")),
                orEmpty(Jsonl.str(line, "defSdkVersion")),
                Jsonl.strArray(line, "sdkEntries"),
                Jsonl.strArray(line, "languages"),
                Jsonl.strArray(line, "scalaVersions"),
                Jsonl.strArray(line, "scalaJars"));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
