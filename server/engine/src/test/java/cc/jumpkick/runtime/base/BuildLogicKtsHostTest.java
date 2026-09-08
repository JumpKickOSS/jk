// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The pieces of the shared {@code .kts} host that hold without starting a child JVM. Running a
 * script end to end is {@link BuildLogicScriptLanguageTest}, which is tagged {@code integration}
 * because it provisions Kotlin.
 *
 * <p>There is nothing here about injecting bindings into script text. That was the old host's
 * job — it wrote {@code val projectDir = Path.of("…")} into a wrapper and hoisted the user's
 * imports above it — and the bindings are now declared by the script definition instead.
 */
class BuildLogicKtsHostTest {

    @Test
    void host_source_ships_in_the_image() throws Exception {
        String src = KtsHostJar.source();
        assertTrue(src.contains("object JkScriptConfig"), "the script definition must be in the shipped source");
        assertTrue(
                src.contains("providedProperties(\"projectDir\""),
                "bindings are declared properties, not injected text — that is what makes one compiled jar "
                        + "reusable across modules");
        assertTrue(src.contains("READY"), "the startup handshake KtsSession waits for");
    }

    /**
     * A script's compiled jar is only valid for the Kotlin that produced it, and for the host source
     * that defined its base class. Both belong in the path, or an upgrade silently reuses bytecode
     * compiled against a different definition.
     */
    @Test
    void host_jar_path_is_keyed_by_kotlin_version() throws Exception {
        Path a = KtsHostJar.jarPath("2.4.10");
        Path b = KtsHostJar.jarPath("2.5.0");
        assertNotEquals(a, b);
        assertEquals(a, KtsHostJar.jarPath("2.4.10"), "the same version must resolve to the same jar");
        assertTrue(a.startsWith(JkDirs.tools()), "a built artifact belongs beside the other provisioned tools");
    }

    @Test
    void host_classpath_carries_the_scripting_host_and_the_compiler() {
        var names = KtsHostJar.kotlinClasspath(Path.of("/kotlin")).stream()
                .map(p -> p.getFileName().toString())
                .toList();
        // main-kts is the fat jar carrying BasicJvmScriptingHost, CompiledScriptJarsCache and the
        // @file: annotation handling the definition reuses.
        assertTrue(names.contains("kotlin-main-kts.jar"));
        assertTrue(names.contains("kotlin-compiler.jar"), "a cache miss still has to compile");
        assertTrue(KtsHostJar.kotlinClasspath(Path.of("/kotlin")).stream()
                .allMatch(p -> p.startsWith(Path.of("/kotlin", "lib"))));
    }

    /**
     * Compiled scripts are derived data: losing one costs a recompile, so they belong in the cache
     * tier. The provisioned tools do not — {@code BuildLogicGroovyHost} learned that the hard way
     * when its jars sat somewhere the retention sweep reclaimed.
     */
    @Test
    void compiled_scripts_live_in_the_cache_tier_not_the_store() {
        Path cache = KtsSession.compiledScriptCache();
        assertTrue(cache.startsWith(JkDirs.cache()));
        assertFalse(cache.startsWith(JkDirs.tools()));
    }
}
