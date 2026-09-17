// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A declared step/packager input (build-plugins plan §3.2). Declaring inputs is the whole caching
 * contract: the engine fingerprints exactly these to key the action cache, so the author never
 * sees action keys, freshness stamps, or CAS paths — and gets correct incrementality for free.
 *
 * @param kind what the input is
 * @param step the producing step's name ({@link Kind#STEP_OUTPUT}) or the module-relative path
 *     ({@link Kind#PROJECT_FILES}) — null for every other kind
 */
public record In(Kind kind, @Nullable String step) {

    public enum Kind {
        /** The module's compiled classes dir (resources already copied in). */
        CLASSES,
        /** The resolved production RUNTIME classpath — jar paths, lock-ordered. */
        RUNTIME_CLASSPATH,
        /**
         * The module's COMPILE classpath — jar paths, lock-ordered: what {@code javac} sees,
         * {@code provided} artifacts included and runtime-only ones absent. For a step that reads
         * contracts out of jars ({@code .proto} files) which a compile-only dependency may carry.
         */
        COMPILE_CLASSPATH,
        /** As {@link #RUNTIME_CLASSPATH}, plus real artifact names + snapshot flags per entry. */
        RUNTIME_ENTRIES,
        /**
         * The test runtime closure as entries — what a forked test JVM sees, with the test-scope
         * artifacts and their coordinates. For a TEST-window step that describes the test
         * classpath to a framework's own bootstrap. Declared instead of {@link #RUNTIME_ENTRIES},
         * not alongside it: a step reads one entry list.
         */
        TEST_RUNTIME_ENTRIES,
        /** The plugin's own validated config table — any config change re-runs. */
        CONFIG,
        /** Another step's declared outputs (chaining, e.g. packaging over an AOT step). */
        STEP_OUTPUT,
        /**
         * A module-relative file or directory the step consumes (a codegen step's real inputs:
         * {@code res}, {@code proto}, {@code AndroidManifest.xml}, …). The engine fingerprints
         * its content recursively; the body reads it via {@code exec.moduleDir()}.
         */
        PROJECT_FILES,
        /**
         * The same module-relative path in every workspace sibling this module depends on that
         * declares the plugin's table: the value under a config key of the sibling's own table (the
         * schema default when its table omits the key). A codegen step whose sources import a
         * sibling's ({@code queue.proto} importing {@code tbmsg.proto} from the module it depends
         * on) reads them as include roots; the engine fingerprints each directory's content.
         */
        SIBLING_PROJECT_FILES,
        /**
         * The remote repositories this module resolves against, as the engine routes them: the
         * {@code [repositories]} set over the built-in remotes, each at the URL jk itself opens
         * (a {@code settings.xml} mirror, Central's mirror while Central refuses this host) with
         * its credential. For a step whose own resolver fetches outside the lock. The key carries
         * the declared set, not the routing: a mirror window opening re-runs nothing.
         */
        REPOSITORIES
    }

    public In {
        Objects.requireNonNull(kind, "kind");
        boolean carriesValue =
                kind == Kind.STEP_OUTPUT || kind == Kind.PROJECT_FILES || kind == Kind.SIBLING_PROJECT_FILES;
        if (carriesValue == (step == null)) {
            throw new IllegalArgumentException(
                    "a value is required for STEP_OUTPUT/PROJECT_FILES/SIBLING_PROJECT_FILES and only for those kinds");
        }
    }

    public static In classes() {
        return new In(Kind.CLASSES, null);
    }

    public static In runtimeClasspath() {
        return new In(Kind.RUNTIME_CLASSPATH, null);
    }

    public static In compileClasspath() {
        return new In(Kind.COMPILE_CLASSPATH, null);
    }

    public static In runtimeEntries() {
        return new In(Kind.RUNTIME_ENTRIES, null);
    }

    public static In testRuntimeEntries() {
        return new In(Kind.TEST_RUNTIME_ENTRIES, null);
    }

    public static In config() {
        return new In(Kind.CONFIG, null);
    }

    public static In stepOutput(String step) {
        return new In(Kind.STEP_OUTPUT, step);
    }

    /** A module-relative file or dir this step consumes (fingerprinted recursively). */
    public static In projectFiles(String relPath) {
        return new In(Kind.PROJECT_FILES, relPath);
    }

    /**
     * The directory each dependency sibling's table names under {@code configKey} — read in the
     * body via {@code exec.siblingFiles(configKey)}.
     */
    public static In siblingProjectFiles(String configKey) {
        return new In(Kind.SIBLING_PROJECT_FILES, configKey);
    }

    /** The module's routed remote repositories — read in the body via {@code exec.repositories()}. */
    public static In repositories() {
        return new In(Kind.REPOSITORIES, null);
    }

    /**
     * The wire spelling: {@code classes}, {@code runtime-classpath}, …, {@code step:<name>},
     * {@code project:<rel>}, {@code sibling:<config key>}.
     */
    public String wireName() {
        if (kind == Kind.STEP_OUTPUT) return "step:" + step;
        if (kind == Kind.PROJECT_FILES) return "project:" + step;
        if (kind == Kind.SIBLING_PROJECT_FILES) return "sibling:" + step;
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static In fromWire(String name) {
        if (name.startsWith("step:")) return stepOutput(name.substring("step:".length()));
        if (name.startsWith("project:")) return projectFiles(name.substring("project:".length()));
        if (name.startsWith("sibling:")) return siblingProjectFiles(name.substring("sibling:".length()));
        return new In(Kind.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_')), null);
    }
}
