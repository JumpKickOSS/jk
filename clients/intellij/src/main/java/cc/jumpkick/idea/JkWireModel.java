// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The engine's {@code ide-model} as {@code jk ide --print-model} prints it, decoded without a JSON
 * library so the plugin stays wire-only. Per-module data arrives as parallel arrays indexed like
 * {@code moduleDirs}; cross-module rows are {@code moduleIndex|…} strings. Jar and JDK paths are
 * absolute. A non-null {@link #error} means the engine could not compute the model.
 */
public final class JkWireModel {

    /** Sibling-ref scope: on the main (and thus test) classpath. */
    public static final String SCOPE_COMPILE = "COMPILE";

    /** Sibling-ref scope: on the test classpath only. */
    public static final String SCOPE_TEST = "TEST";

    /** Sibling-ref scope: test-scoped, and the sibling's test classes join the test classpath. */
    public static final String SCOPE_TEST_KIND = "TEST_KIND";

    /** Sibling-ref scope: a main dependency whose test classes also join the test classpath. */
    public static final String SCOPE_COMPILE_TEST_KIND = "COMPILE+TEST_KIND";

    /** A resolved JDK: {@code name} is the stable IntelliJ SDK name ({@code jk-temurin-25}). */
    public record Sdk(String stableName, String name, int level, String home, String version) {}

    /** One workspace module; {@code jdt*} dirs are the IDE-owned compiler outputs. */
    public record Module(
            String dir,
            String name,
            int javaRelease,
            @Nullable String mainClass,
            String classesDir,
            String testClassesDir,
            String jdtClassesDir,
            String jdtTestClassesDir,
            String genSrcDir,
            String genTestSrcDir,
            Sdk sdk) {}

    /** An external library by coordinate; {@code sources} is null when no sources jar resolved. */
    public record Lib(
            String name, String file, String jar, @Nullable String sources) {}

    /** A cross-module edge from module {@code module} to the module named {@code name}. */
    public record SiblingRef(int module, String name, String scope) {}

    /** A module's external-library reference with its raw jk scopes ({@code MAIN}, {@code TEST}…). */
    public record LibEntry(int module, String libName, List<String> scopes) {}

    /** An annotation-processor jar on module {@code module}'s processor path. */
    public record ProcessorJar(int module, String jar) {}

    /** A JDK to register in the IDE under a stable name. */
    public record SdkEntry(String name, String home, String version) {}

    public final @Nullable String error;
    public final @NotNull String wsRoot;
    public final @NotNull String rootName;
    public final boolean workspace;
    public final @NotNull List<Module> modules;
    public final @NotNull List<Lib> libs;
    public final @NotNull List<SiblingRef> siblingRefs;
    public final @NotNull List<LibEntry> libEntries;
    public final @NotNull List<ProcessorJar> processorJars;
    public final @NotNull Sdk defaultSdk;
    public final @NotNull List<SdkEntry> sdkEntries;

    private JkWireModel(Map<String, Object> o) {
        Object err = o.get("error");
        this.error = err instanceof String s && !s.isBlank() ? s : null;
        this.wsRoot = str(o, "wsRoot");
        this.rootName = str(o, "rootName");
        this.workspace = Boolean.TRUE.equals(o.get("workspace"));

        List<String> dirs = strings(o, "moduleDirs");
        List<Module> mods = new ArrayList<>(dirs.size());
        for (int i = 0; i < dirs.size(); i++) {
            String main = at(o, "mainClasses", i);
            mods.add(new Module(
                    dirs.get(i),
                    at(o, "names", i),
                    parseInt(at(o, "javaReleases", i)),
                    main.isEmpty() ? null : main,
                    at(o, "classesDirs", i),
                    at(o, "testClassesDirs", i),
                    at(o, "jdtClassesDirs", i),
                    at(o, "jdtTestClassesDirs", i),
                    at(o, "genSrcDirs", i),
                    at(o, "genTestSrcDirs", i),
                    new Sdk(
                            at(o, "sdkStableNames", i),
                            at(o, "sdkNames", i),
                            parseInt(at(o, "sdkLevels", i)),
                            at(o, "sdkHomes", i),
                            at(o, "sdkVersions", i))));
        }
        this.modules = List.copyOf(mods);

        List<String> libNames = strings(o, "libNames");
        List<Lib> libRows = new ArrayList<>(libNames.size());
        for (int i = 0; i < libNames.size(); i++) {
            String sources = at(o, "libSources", i);
            libRows.add(new Lib(
                    libNames.get(i), at(o, "libFiles", i), at(o, "libJars", i), sources.isEmpty() ? null : sources));
        }
        this.libs = List.copyOf(libRows);

        List<SiblingRef> refs = new ArrayList<>();
        for (String row : strings(o, "siblingRefs")) {
            String[] p = row.split("\\|", 3);
            if (p.length == 3) refs.add(new SiblingRef(parseInt(p[0]), p[1], p[2]));
        }
        this.siblingRefs = List.copyOf(refs);

        List<LibEntry> entries = new ArrayList<>();
        for (String row : strings(o, "libEntries")) {
            String[] p = row.split("\\|", 3);
            if (p.length == 3) {
                List<String> scopes = new ArrayList<>();
                for (String s : p[2].split(",")) if (!s.isBlank()) scopes.add(s.strip());
                entries.add(new LibEntry(parseInt(p[0]), p[1], List.copyOf(scopes)));
            }
        }
        this.libEntries = List.copyOf(entries);

        List<ProcessorJar> procs = new ArrayList<>();
        for (String row : strings(o, "processorJars")) {
            String[] p = row.split("\\|", 2);
            if (p.length == 2) procs.add(new ProcessorJar(parseInt(p[0]), p[1]));
        }
        this.processorJars = List.copyOf(procs);

        Object level = o.get("defSdkLevel");
        this.defaultSdk = new Sdk(
                str(o, "defSdkStableName"),
                str(o, "defSdkName"),
                level instanceof Number n ? n.intValue() : 0,
                str(o, "defSdkHome"),
                str(o, "defSdkVersion"));

        List<SdkEntry> sdks = new ArrayList<>();
        for (String row : strings(o, "sdkEntries")) {
            String[] p = row.split("\\|", 3);
            if (p.length == 3) sdks.add(new SdkEntry(p[0], p[1], p[2]));
        }
        this.sdkEntries = List.copyOf(sdks);
    }

    public int moduleCount() {
        return modules.size();
    }

    /** The module dirs in wire order (the index space of every {@code module} field). */
    public List<String> moduleDirs() {
        List<String> out = new ArrayList<>(modules.size());
        for (Module m : modules) out.add(m.dir());
        return out;
    }

    /**
     * Decode stdout: the last {@code {"type"} object wins when chrome or an earlier object leaked
     * onto the stream, so a warm-up line before the model does not break the parse.
     */
    public static @NotNull JkWireModel parse(@NotNull String json) {
        int start = json.lastIndexOf("{\"type\"");
        if (start < 0) start = json.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("ide-model JSON: no object on stdout");
        return new JkWireModel(JkJson.object(JkJson.parse(json, start), Map.of()));
    }

    private static String str(Map<String, Object> o, String key) {
        return o.get(key) instanceof String s ? s : "";
    }

    private static List<String> strings(Map<String, Object> o, String key) {
        if (!(o.get(key) instanceof List<?> raw)) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (Object v : raw) out.add(v instanceof String s ? s : "");
        return out;
    }

    /** Element {@code i} of a parallel array, or {@code ""} when the array is short or absent. */
    private static String at(Map<String, Object> o, String key, int i) {
        if (!(o.get(key) instanceof List<?> raw) || i >= raw.size()) return "";
        return raw.get(i) instanceof String s ? s : "";
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
