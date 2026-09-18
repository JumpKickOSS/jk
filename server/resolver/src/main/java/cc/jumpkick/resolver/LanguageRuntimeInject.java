// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.layout.Languages;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Puts language runtimes into the main lock graph so packaging can nest them. Engine classpath
 * injection covers {@code jk run}/tests; boot-jar nesting only sees lock artifacts.
 *
 * <p>{@code putIfAbsent}: an explicit user/Grails BOM dep wins. Version follows the project's
 * {@code kotlin}/{@code groovy}/{@code scala} pin when it has a literal; otherwise a floating major
 * of the current jk default so PubGrub still picks a concrete release at lock time.
 */
public final class LanguageRuntimeInject {

    private LanguageRuntimeInject() {}

    /**
     * The compiler versions the lock is pinning, resolved before the solve. A stdlib is the
     * compiler's stdlib: injected with an exact selector equal to the compiler's version rather
     * than the manifest's floating one, so {@code scala = "3.8.4"} cannot lock a 3.8.4 compiler
     * beside a 3.9.0 library (or the reverse skew, which is a worker crash). {@code null} means
     * the language is not pinned here and the manifest selector is used as written.
     */
    public record ToolVersions(
            @Nullable String kotlin,
            @Nullable String scala,
            @Nullable String groovy) {
        public static final ToolVersions NONE = new ToolVersions(null, null, null);

        static @Nullable VersionSelector exactOr(@Nullable String version, @Nullable VersionSelector declared) {
            return version == null || version.isBlank() ? declared : new VersionSelector.Exact("=" + version, version);
        }
    }

    /**
     * What one inject did: the module keys it added (the skip-list for the exact-root BOM strip)
     * and the notes the lock carries for what it moved.
     */
    record Injected(Set<String> runtimes, List<String> notes) {}

    /** The Kotlin coordinates published at the compiler's version, one family with {@code kotlin-stdlib}. */
    private static final List<String> KOTLIN_FAMILY = List.of(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-reflect",
            "org.jetbrains.kotlin:kotlin-test");

    /**
     * Inject Groovy/Kotlin/Scala runtimes when the project has sources of that language.
     *
     * @return the module keys this call added (skip-list for the exact-root BOM strip) and its notes
     */
    static Injected inject(
            JkBuild project,
            @Nullable Path projectDir,
            Map<String, String> bomConstraints,
            LinkedHashMap<String, Dependency> mainDeduped,
            ToolVersions tools) {
        Set<String> added = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        Project p = project.project();
        // Same inference the engine uses to enable lanes: an unpinned project with
        // src/main/groovy compiles the groovy lane, so its runtime must land in the lock too —
        // jk run and packaging read the lock only. Pin-only keying shipped jars that died with
        // NoClassDefFoundError: groovy/lang/GroovyObject.
        Languages langs = projectDir != null
                ? Languages.resolve(p, projectDir)
                : new Languages(true, p.isKotlin(), p.isGroovy(), p.isScala());
        // Only when the language has actual sources (src/ or plugin-contributed roots like
        // grails-app/): a bare `kotlin = "2.1.0"` pin on a sourceless module pins the COMPILER
        // (lock.kotlin) but produces no classes — injecting its runtime made such locks fail
        // against repos that don't host the stdlib.
        if (langs.groovy() && hasLangSources(projectDir, ".groovy")) {
            VersionSelector groovy = ToolVersions.exactOr(tools.groovy(), p.groovy());
            addRuntime(bomConstraints, mainDeduped, added, "org.apache.groovy:groovy", groovy, "5");
        }
        if (langs.kotlin() && hasLangSources(projectDir, ".kt")) {
            if (tools.kotlin() != null) alignKotlinFamily(bomConstraints, mainDeduped, tools.kotlin(), notes);
            VersionSelector kotlin = ToolVersions.exactOr(tools.kotlin(), p.kotlin());
            addRuntime(bomConstraints, mainDeduped, added, "org.jetbrains.kotlin:kotlin-stdlib", kotlin, "2");
        }
        if (langs.scala() && hasLangSources(projectDir, ".scala")) {
            VersionSelector scala = ToolVersions.exactOr(tools.scala(), p.scala());
            addRuntime(bomConstraints, mainDeduped, added, "org.scala-lang:scala3-library_3", scala, "3");
            // On 3.8+ the stub's own `scala-library` edge is the real stdlib, declared as a Maven soft
            // version that highest-wins would float past the compiler (3.8.4 stub, 3.9.0 library).
            // Root it exactly too, so the lock carries one Scala version.
            if (tools.scala() != null && ScalaVersions.stdlibIsScalaLibrary(tools.scala())) {
                addRuntime(bomConstraints, mainDeduped, added, "org.scala-lang:scala-library", scala, "3");
            }
        }
        return new Injected(added, notes);
    }

    /**
     * The compiler's stdlib family is the compiler's: every {@link #KOTLIN_FAMILY} coordinate the
     * platform table manages at another version, and every declared root pinned at one, follows
     * {@code compiler} — the version the module compiles with — so one stdlib reaches the compile
     * classpath and the suite runs the classes the compiler wrote against the library it wrote them
     * for. A coordinate nothing manages is left to the solve; one note names what moved.
     */
    private static void alignKotlinFamily(
            Map<String, String> bomConstraints,
            LinkedHashMap<String, Dependency> mainDeduped,
            String compiler,
            List<String> notes) {
        Map<String, String> moved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bomConstraints.entrySet()) {
            if (kotlinFamily(e.getKey()) && !compiler.equals(e.getValue())) {
                moved.put(e.getKey(), e.getValue());
                e.setValue(compiler);
            }
        }
        for (Map.Entry<String, Dependency> e : mainDeduped.entrySet()) {
            Dependency d = e.getValue();
            if (!kotlinFamily(d.module()) || d.isPlatformManaged()) continue;
            String lit = versionLiteral(d.version());
            if (lit == null || lit.isBlank() || compiler.equals(lit)) continue;
            moved.put(d.module(), lit);
            e.setValue(d.withVersion(new VersionSelector.Exact("=" + compiler, compiler)));
        }
        if (moved.isEmpty()) return;
        StringBuilder note = new StringBuilder("the module compiles with Kotlin ")
                .append(compiler)
                .append(", so its stdlib family follows the compiler: ");
        boolean first = true;
        for (Map.Entry<String, String> e : moved.entrySet()) {
            if (!first) note.append(", ");
            first = false;
            note.append(e.getKey())
                    .append(" at ")
                    .append(compiler)
                    .append(" instead of ")
                    .append(e.getValue());
        }
        note.append(" — one stdlib on the compile classpath, the one the compiled classes were written against");
        notes.add(note.toString());
    }

    private static boolean kotlinFamily(String module) {
        for (String family : KOTLIN_FAMILY) {
            if (module.equals(family) || module.startsWith(family + "-")) return true;
        }
        return false;
    }

    /** Where the Scala 3 stdlib lives, by compiler version. */
    static final class ScalaVersions {
        private ScalaVersions() {}

        /**
         * From 3.8 the stdlib ships as {@code org.scala-lang:scala-library} at the compiler's own
         * version and {@code scala3-library_3} is an empty stub over it; before 3.8 the library was
         * the 2.13 {@code scala-library}, whose version has nothing to do with the compiler's.
         */
        static boolean stdlibIsScalaLibrary(String scalaVersion) {
            String[] parts = scalaVersion.split("[.-]");
            if (parts.length < 2) return false;
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major > 3 || (major == 3 && minor >= 8);
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** True when any {@code ext} source exists under src/ or a plugin-contributed root. */
    private static boolean hasLangSources(@Nullable Path projectDir, String ext) {
        if (projectDir == null) return true; // no dir context — keep the inject (fail-safe)
        if (Languages.anySourceUnder(projectDir.resolve("src"), ext)) return true;
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(projectDir)) {
            if (Languages.anySourceUnder(projectDir.resolve(root.relative()), ext)) {
                return true;
            }
        }
        return false;
    }

    /** Inject one runtime; BOM-following injects (no exact pin) join the strip skip-list. */
    private static void addRuntime(
            Map<String, String> bomConstraints,
            LinkedHashMap<String, Dependency> mainDeduped,
            Set<String> added,
            String module,
            @Nullable VersionSelector declared,
            String fallbackMajor) {
        String pinLit = declared != null ? versionLiteral(declared) : null;
        boolean pinned = (pinLit != null && !pinLit.isBlank())
                || declared instanceof VersionSelector.Latest
                || declared instanceof VersionSelector.Snapshot;
        Dependency dep = new Dependency(module, runtimeSelector(bomConstraints, module, declared, fallbackMajor));
        // mainDeduped is keyed by packageKey (so a jar and a test-jar of one GA can both root), so
        // probe with the same key — a bare-GA probe never sees the user's own dep and injects a
        // second root for the same solver package. `added` stays GA-keyed: stripBomForExactRoots
        // matches it against Dependency.module().
        if (mainDeduped.putIfAbsent(dep.packageKey(), dep) == null && !pinned) {
            added.add(module);
        }
    }

    /**
     * An explicit exact pin literal wins (the user's — or a framework scaffold's — deliberate
     * choice; it strips the BOM entry via the normal exact-root rule, which Grails needs: its
     * M4 bom manages a groovy OLDER than grails-core requires). Without a literal, a platform
     * that manages the GA owns the version (Maven parity — the inject then skips the strip so
     * every edge agrees); else floating major.
     */
    private static VersionSelector runtimeSelector(
            Map<String, String> bomConstraints,
            String module,
            @Nullable VersionSelector declared,
            String fallbackMajor) {
        if (declared instanceof VersionSelector.Latest || declared instanceof VersionSelector.Snapshot) {
            // Floating keywords are a deliberate choice, same as an exact pin: they override a
            // platform that manages this GA (Grails' bom pins an older groovy than latest).
            return declared;
        }
        String lit = declared != null ? versionLiteral(declared) : null;
        if (lit != null && !lit.isBlank()) {
            return VersionSelector.parse("=" + lit);
        }
        String managed = bomConstraints.get(module);
        if (managed != null && !managed.isBlank()) {
            return VersionSelector.parse("=" + managed);
        }
        return languageRuntimeSelector(declared, fallbackMajor);
    }

    /** Exact pin when the project declared a version literal; else floating major of {@code fallbackMajor}. */
    private static VersionSelector languageRuntimeSelector(@Nullable VersionSelector declared, String fallbackMajor) {
        if (declared != null) {
            String lit = versionLiteral(declared);
            if (lit != null && !lit.isBlank()) {
                return VersionSelector.parse("=" + lit);
            }
        }
        return VersionSelector.parse("^" + fallbackMajor);
    }

    /** Concrete version literal, or {@code null} for range / latest / snapshot. */
    private static @Nullable String versionLiteral(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range ignored -> null;
            case VersionSelector.Latest ignored -> null;
            case VersionSelector.Snapshot ignored -> null;
        };
    }
}
