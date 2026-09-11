// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackagerSpec;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The signing identity is part of the artifact, so the keystore must be a declared packager input.
 * It was not: the packager key carried the configured store <em>path</em> (inside the config token)
 * and never its content, so rotating a key in place shipped an APK still carrying the old
 * signature — restored from cache and reported up-to-date. A debug build had it worse: the keystore
 * was generated into a fresh temp dir every run, so no two builds shared a signature at all.
 */
class SigningInputTest {

    @Test
    void the_debug_keystore_is_a_declared_input_of_the_apk_packager() {
        assertThat(inputsOf(packagerOf(config(Map.of())))).contains(debugKeystoreInput());
    }

    @Test
    void the_debug_keystore_is_a_declared_input_of_the_aab_packager() {
        PackagerSpec aab = packagerOf(config(Map.of("build-type", "release")));

        assertThat(aab.name()).isEqualTo("aab");
        assertThat(inputsOf(aab)).contains(debugKeystoreInput());
    }

    /** A configured release store is what that build signs with, so it is what the key must carry. */
    @Test
    void a_configured_release_store_replaces_the_debug_keystore_in_the_key() {
        List<In> inputs = inputsOf(packagerOf(config(Map.of("signing.store-file", "/keys/release.jks"))));

        assertThat(inputs).contains(In.projectFiles("/keys/release.jks"));
        assertThat(inputs)
                .as("a release build never touches the debug identity")
                .doesNotContain(debugKeystoreInput());
    }

    /** An AAR is never signed — declaring a keystore there would be a key that means nothing. */
    @Test
    void a_library_declares_no_keystore_input() {
        PackagerSpec aar = packagerOf(config(Map.of("library", Boolean.TRUE)));

        assertThat(aar.name()).isEqualTo("aar");
        assertThat(inputsOf(aar)).noneSatisfy(in -> assertThat(in.wireName()).contains("keystore"));
    }

    /** The keystore rides the shared declared-input vocabulary, not a private convention. */
    @Test
    void the_keystore_input_is_a_content_fingerprinted_file_input() {
        In keystore = Signing.keystoreInput(config(Map.of("signing.store-file", "/keys/release.jks")));

        assertThat(keystore.kind()).isEqualTo(In.Kind.PROJECT_FILES);
        assertThat(keystore.wireName()).isEqualTo("project:/keys/release.jks");
    }

    /** {@code debug-store-dir} moves the debug identity, so it must move the declared input too. */
    @Test
    void a_configured_debug_store_dir_redirects_the_debug_keystore_input() {
        List<In> inputs = inputsOf(packagerOf(config(Map.of("debug-store-dir", "/ci/android-home"))));

        assertThat(inputs)
                .contains(In.projectFiles(
                        DebugKeystore.path(Path.of("/ci/android-home")).toString()));
        assertThat(inputs).doesNotContain(debugKeystoreInput());
    }

    /**
     * Unset, the fallback is the ecosystem's stable dir — asserted on the path chosen, never by
     * generating anything there.
     */
    @Test
    void an_unset_debug_store_dir_falls_back_to_the_stable_dir() {
        assertThat(Signing.debugStoreDir(config(Map.of()))).isEqualTo(DebugKeystore.stableDir());
    }

    private static In debugKeystoreInput() {
        return In.projectFiles(DebugKeystore.path(DebugKeystore.stableDir()).toString());
    }

    private static PluginConfig config(Map<String, Object> values) {
        Map<String, Object> all =
                new LinkedHashMap<>(Map.of("namespace", "com.example.app", "compile-sdk", 36L, "min-sdk", 24L));
        all.putAll(values);
        return new PluginConfig("android", all);
    }

    private static List<In> inputsOf(PackagerSpec spec) {
        return spec.declaredInputs();
    }

    /** Register the plugin against {@code config} and hand back the packager it chose. */
    private static PackagerSpec packagerOf(PluginConfig config) {
        Registrations registrations = new Registrations(config);
        new AndroidPlugin().register(registrations);
        return Objects.requireNonNull(registrations.packager, "android always registers exactly one packager");
    }

    /** A recording {@link BuildPluginContext} — registration records, it never executes. */
    private static final class Registrations implements BuildPluginContext {
        private final PluginConfig config;
        private final List<TaskSpec> tasks = new ArrayList<>();
        private @Nullable PackagerSpec packager;

        private Registrations(PluginConfig config) {
            this.config = config;
        }

        @Override
        public PluginConfig config() {
            return config;
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("com.example", "app", "0.1.0", 25, null, false, false, Map.of());
        }

        @Override
        public void task(TaskSpec spec) {
            tasks.add(spec);
        }

        @Override
        public void packaging(PackagerSpec spec) {
            packager = spec;
        }

        @Override
        public void command(PluginCommandSpec spec) {}
    }
}
