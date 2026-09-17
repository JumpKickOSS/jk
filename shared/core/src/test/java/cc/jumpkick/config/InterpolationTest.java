// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * where {@code ${VAR}} is honoured, and that everywhere else it is a loud error.
 *
 * <p>The whitelist is the reproducibility fence. {@code jk.toml} plus {@code jk-lock.toml} must fully
 * describe the artifact, and the action cache makes a leak worse than plain non-determinism: an
 * environment-sourced value inside a cache key means CI and a laptop never share cache, and one that
 * reaches a compiler flag <em>without</em> reaching the key produces silently stale artifacts.
 */
class InterpolationTest {

    private static final String PROJECT = """
            group   = "com.example"
            name    = "m"
            version = "1.0.0"
            """;

    private static void parse(String toml) {
        JkBuildParser.parse(toml);
    }

    // ---- allowed ----------------------------------------------------------------

    @Test
    void repository_credentials_may_interpolate() {
        assertThatCode(() -> parse(PROJECT + """
                        [repositories.r]
                        url = "https://nexus.example/repo/"
                        username = "${REPO_USER}"
                        password = "${REPO_PASS}"
                        """)).doesNotThrowAnyException();
    }

    @Test
    void repository_object_store_keys_may_interpolate() {
        assertThatCode(() -> parse(PROJECT + """
                        [repositories.s3]
                        url = "s3://bucket/maven"
                        access-key = "${AWS_KEY}"
                        secret-key = "${AWS_SECRET}"
                        session-token = "${AWS_TOKEN}"
                        region = "${AWS_REGION}"
                        endpoint = "${AWS_ENDPOINT}"
                        """)).doesNotThrowAnyException();
    }

    @Test
    void test_env_values_may_interpolate() {
        assertThatCode(() -> parse(PROJECT + """
                        [test]
                        env = [{ TOKEN = "${CI_TOKEN}", HOME_DIR = "${target}/h" }]
                        """)).doesNotThrowAnyException();
    }

    // ---- refused, because these decide what gets built -------------------------

    @Test
    void a_repository_url_may_not_interpolate() {
        // The lockfile records the URL, so an env-dependent one makes a committed lock differ
        // between machines built from the same commit.
        assertThatThrownBy(() -> parse(PROJECT + """
                        [repositories]
                        r = "${MIRROR}/maven"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.r")
                .hasMessageContaining("MIRROR");
    }

    @Test
    void a_url_inside_a_repository_table_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [repositories.r]
                        url = "${MIRROR}/maven"
                        username = "${REPO_USER}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.r.url");
    }

    @Test
    void a_version_may_not_interpolate() {
        assertThatThrownBy(() -> parse("""
                        group   = "com.example"
                        name    = "m"
                        version = "${MY_VERSION}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void a_dependency_coordinate_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [dependencies]
                        thing = { group = "com.example", name = "thing", version = "${DEP_VERSION}" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("dependencies.thing.version");
    }

    @Test
    void compile_shaping_build_keys_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [build]
                        extra-src = ["${EXTRA_SRC}"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("build.extra-src");

        assertThatThrownBy(() -> parse(PROJECT + """
                        [build]
                        ksp-options = ["room.schemaLocation=${SCHEMA_DIR}"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("build.ksp-options");
    }

    @Test
    void array_of_tables_entries_may_not_interpolate() {
        // Array elements are walked too, so a reference cannot hide inside one.
        assertThatThrownBy(() -> parse(PROJECT + """
                        [[kotlin-plugins]]
                        coordinate = "org.jetbrains.kotlin:kotlin-noarg:${KOTLIN_VER}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("kotlin-plugins");
    }

    @Test
    void a_jar_manifest_attribute_may_not_interpolate() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [manifest]
                        Build-Number = "${CI_BUILD}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("manifest.Build-Number");
    }

    @Test
    void the_error_names_every_offending_position() {
        assertThatThrownBy(() -> parse("""
                        group   = "com.example"
                        name    = "m"
                        version = "${A}"

                        [manifest]
                        X = "${B}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("manifest.X");
    }

    // ---- shape of the matcher --------------------------------------------------

    @Test
    void a_dollar_that_is_not_a_reference_is_left_alone() {
        // A literal '$' — a password rule, a shell snippet — must not trip the guard.
        assertThatCode(() -> parse(PROJECT + """
                        [manifest]
                        Note = "costs $5 and $ is fine"
                        """)).doesNotThrowAnyException();
    }

    /** A generator's {@code ${in}} / {@code ${out}} vocabulary is the worker's, not the environment's. */
    @Test
    void a_generate_entrys_args_carry_the_generators_vocabulary() {
        parse(PROJECT + """
                [generate.wire]
                tool = "com.squareup.wire:wire-compiler:5.5.1"
                main = "com.squareup.wire.WireCompiler"
                unpack = "io.zipkin.proto3:zipkin-proto3:1.0.0"
                args = ["--proto_path=${unpacked}", "--java_out=${out}", "${in}", "${module.dir}/x"]
                """);
        assertThat(Interpolation.allowed("generate.wire.args[0]")).isTrue();
        assertThat(Interpolation.allowed("generate.wire.tool")).isFalse();
        assertThat(Interpolation.allowed("generate.wire.inputs[0]")).isFalse();
        assertThatThrownBy(() -> parse(PROJECT + """
                        [generate.wire]
                        tool = "com.squareup.wire:wire-compiler:${WIRE}"
                        inputs = ["x.proto"]
                        """)).hasMessageContaining("generate.wire.tool (${WIRE})");
    }

    @Test
    void whitelist_matching_is_exact_about_depth_and_names() {
        assertThat(Interpolation.allowed("repositories.r.username")).isTrue();
        // Not a credential field.
        assertThat(Interpolation.allowed("repositories.r.url")).isFalse();
        // Right leaf name, wrong place.
        assertThat(Interpolation.allowed("build.username")).isFalse();
        // A bare `*` still never matches an array element: the indexed form has to be asked for.
        assertThat(Interpolation.allowed("repositories.r[0].username")).isFalse();
    }

    @Test
    void the_test_env_array_expands_in_values_and_not_in_names() {
        // [test] env is an array of "forward this name" strings and "set these" tables. The value
        // half is a whitelisted slot; the name half is not, so the two halves cannot be confused
        // even though they are elements of one array. This is the guard doing that, not the parser.
        assertThat(Interpolation.allowed("test.env[0].ANYTHING")).isTrue();
        assertThat(Interpolation.allowed("test.env[12].ANYTHING")).isTrue();
        // The bare-name position — `env = ["${FOO}"]` — stays an error.
        assertThat(Interpolation.allowed("test.env[0]")).isFalse();
        // And the table shape it replaced is no longer a position at all.
        assertThat(Interpolation.allowed("test.env.ANYTHING")).isFalse();
        // A subscript has to be an index, not a name that happens to end in brackets.
        assertThat(Interpolation.allowed("test.env[x].ANYTHING")).isFalse();
    }

    @Test
    void expand_is_strict_about_an_unset_variable() {
        assertThatThrownBy(() -> Interpolation.expand("${NOPE}", "[test].env.X", name -> null))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.X")
                .hasMessageContaining("NOPE");

        assertThat(Interpolation.expand("${A}-x", "pos", name -> "v")).isEqualTo("v-x");
        assertThat(Interpolation.expand("plain", "pos", name -> null)).isEqualTo("plain");
        assertThat(Interpolation.expand(null, "pos", name -> null)).isNull();
    }

    /**
     * A quoted table key may contain dots — {@code [repositories."nexus.internal"]} is one
     * repository named {@code nexus.internal} — and its credentials sit at the whitelisted
     * position however many dots the name carries.
     */
    @Test
    void a_dotted_repository_name_still_allows_credentials() {
        assertThatCode(() -> parse(PROJECT + """
                        [repositories."nexus.internal"]
                        url = "https://nexus.internal/repo/"
                        username = "${REPO_USER}"
                        password = "${REPO_PASS}"
                        """)).doesNotThrowAnyException();
        assertThatThrownBy(() -> parse(PROJECT + """
                        [repositories."nexus.internal"]
                        url = "${REPO_URL}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("nexus.internal");
    }

    /**
     * The offender is spelled the way the manifest spells it: a key TOML would make the user
     * quote is quoted in the message, so {@code repositories."nexus.internal".url} reads as one
     * repository named {@code nexus.internal} and not as a three-level table; an array subscript
     * stays outside the quotes.
     */
    @Test
    void the_offender_spells_a_dotted_key_quoted_as_the_manifest_does() {
        assertThatThrownBy(() -> parse(PROJECT + """
                        [repositories."nexus.internal"]
                        url = "${REPO_URL}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.\"nexus.internal\".url (${REPO_URL})")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("repositories.nexus.internal.url"));
        assertThatThrownBy(() -> parse(PROJECT + """
                        [test]
                        env = ["${FOO}"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("test.env[0] (${FOO})");
    }

    /**
     * A quoted key may carry any character a basic string can, a newline included; the spelling
     * quotes it like any other non-bare key instead of refusing the segment.
     */
    @Test
    void a_key_with_a_newline_is_spelled_quoted() {
        assertThat(Interpolation.spell(List.of("repositories", "a\nb", "url[0]")))
                .isEqualTo("repositories.\"a\nb\".url[0]");
    }
}
