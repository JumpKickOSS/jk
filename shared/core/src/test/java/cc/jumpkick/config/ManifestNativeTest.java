// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import org.junit.jupiter.api.Test;

/**
 * {@code [native] metadata-repository} — the GraalVM reachability-metadata repository release.
 *
 * <p>It was {@code ReachabilityMetadata.VERSION}, a {@code static final String} in the engine. A
 * user could not ask for a newer repository, could not hold an older one, and could not see which
 * one their image had been built against. It is a manifest key now, in the dependency version
 * grammar, and {@code jk lock} resolves it like any other floating pin.
 */
class ManifestNativeTest {

    private static JkBuild.NativeConfig nativeConfig(String toml) {
        return JkBuildParser.parse("name = \"widget\"\n" + toml)
                .nativeConfigOpt()
                .orElseThrow();
    }

    @Test
    void an_omitted_key_leaves_the_latest_default() {
        assertThat(nativeConfig("[native]\n").metadataRepository())
                .isEqualTo(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT);
        assertThat(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT).isInstanceOf(VersionSelector.Latest.class);
    }

    /** The same grammar dependencies use, decorations and all — not a bare version string. */
    @Test
    void the_dependency_version_grammar_parses() {
        assertThat(nativeConfig("[native]\nmetadata-repository = \"=1.1.4\"\n").metadataRepository())
                .isEqualTo(new VersionSelector.Exact("=1.1.4", "1.1.4"));
        assertThat(nativeConfig("[native]\nmetadata-repository = \"^1.0.0\"\n").metadataRepository())
                .isEqualTo(new VersionSelector.Caret("^1.0.0", "1.0.0"));
        assertThat(nativeConfig("[native]\nmetadata-repository = \"~1.2.3\"\n").metadataRepository())
                .isEqualTo(new VersionSelector.Tilde("~1.2.3", "1.2.3"));
        assertThat(nativeConfig("[native]\nmetadata-repository = \">=1.2,<2\"\n")
                        .metadataRepository())
                .isEqualTo(new VersionSelector.Range(">=1.2,<2"));
    }

    /**
     * Bare is a caret floor, exactly as {@code [dependencies] foo = "1.1"} is. Reading it as an
     * exact pin would make the one grammar mean two things depending on which table it is in.
     */
    @Test
    void a_bare_version_floats_like_a_dependency() {
        assertThat(nativeConfig("[native]\nmetadata-repository = \"1.1\"\n").metadataRepository())
                .isEqualTo(new VersionSelector.Caret("1.1", "1.1"));
    }

    @Test
    void a_blank_selector_is_rejected() {
        assertThatThrownBy(() -> nativeConfig("[native]\nmetadata-repository = \"  \"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("metadata-repository");
    }

    /** No {@code [native]} table means no repository to lock and nothing to fetch. */
    @Test
    void no_native_table_declares_no_repository() {
        assertThat(JkBuildParser.parse("name = \"widget\"\n").nativeConfigOpt()).isEmpty();
    }
}
