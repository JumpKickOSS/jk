// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link MachineConfig} itself, and the fold: every machine-config reader gets its precedence from
 * this one type, so a change here moves all of them.
 *
 * <p>The table below is the fold proof, and it is written to fail if the fold is undone. Each row is
 * one reader, exercised through its real {@code resolve} seam, and the four arms are chosen so no
 * two of them can be satisfied by the same value: the built-in, the file value and the env value are
 * three different numbers, and the fourth arm feeds an env value the setting's own predicate
 * rejects. Delete the {@code valid.test(...)} call in {@link MachineConfig#layerOver} and every row
 * with a range rule fails at once; reverse the layer order and every row fails.
 */
class MachineConfigTest {

    // ---- the owner ---------------------------------------------------------

    @Test
    void highest_valid_layer_wins_and_an_invalid_one_falls_through() {
        MachineConfig<Integer> port = MachineConfig.of(8910, p -> p >= 0 && p <= 65535);

        assertThat(port.layer()).isEqualTo(8910);
        assertThat(port.layer(null, null)).isEqualTo(8910);
        assertThat(port.layer(null, 1)).isEqualTo(1);
        assertThat(port.layer(2, 1)).isEqualTo(2);
        // The high layer decoded, but the value is not one: it falls through, it does not fail.
        assertThat(port.layer(70_000, 1)).isEqualTo(1);
        assertThat(port.layer(70_000, -1)).isEqualTo(8910);
    }

    @Test
    void the_floor_is_trusted_because_the_code_wrote_it() {
        // A disk-clamped or CI-aware default is the code's own number, so the predicate is not
        // applied to it — otherwise a host-specific floor could be rejected into the built-in.
        MachineConfig<Integer> positive = MachineConfig.of(4, i -> i > 0);
        assertThat(positive.layerOver(-99, -1)).isEqualTo(-99);
    }

    @Test
    void accept_judges_one_layer_by_the_same_rule() {
        MachineConfig<Double> gb = MachineConfig.of(4.0, d -> d > 0);
        assertThat(gb.accept(0.0)).isNull();
        assertThat(gb.accept(2.0)).isEqualTo(2.0);
        assertThat(gb.accept(null)).isNull();
    }

    @Test
    void without_a_predicate_anything_that_decoded_is_a_value() {
        MachineConfig<Boolean> flag = MachineConfig.of(true);
        assertThat(flag.layer(false)).isFalse();
        assertThat(flag.layer((Boolean) null)).isTrue();
    }

    // ---- the fold ----------------------------------------------------------

    /**
     * One machine-config reader, reduced to a single field.
     *
     * @param builtIn what the reader answers with no file and no env
     * @param fileBody a {@code config.toml} that sets the field to {@code fromFile}
     * @param envName the variable for the same field, or {@code null} when the reader has no env
     *     layer ({@link JkTemplatesConfig})
     * @param invalidEnv a value that decodes but the setting's predicate rejects, or {@code null}
     *     when the field's decode <em>is</em> its validation (booleans, free strings)
     */
    record Reader(
            String name,
            Object builtIn,
            String fileBody,
            Object fromFile,
            String envName,
            String validEnv,
            Object fromEnv,
            String invalidEnv,
            Read read) {
        @Override
        public String toString() {
            return name;
        }
    }

    interface Read {
        Object of(Path userConfig, Function<String, String> env) throws IOException;
    }

    static List<Reader> readers() {
        return List.of(
                new Reader(
                        "JkEngineConfig.max-heap-mb",
                        256,
                        "[engine]\nmax-heap-mb = 111\n",
                        111,
                        "JK_ENGINE_MAX_HEAP_MB",
                        "222",
                        222,
                        "-5",
                        (f, e) -> JkEngineConfig.resolve(f, e).maxHeapMb()),
                new Reader(
                        "JkCacheConfig.prune-interval-days",
                        7,
                        "[cache]\nprune-interval-days = 11\n",
                        11,
                        "JK_PRUNE_INTERVAL_DAYS",
                        "22",
                        22,
                        "-3",
                        (f, e) -> JkCacheConfig.resolve(f, e, (JkCacheConfig.DiskSpace) null)
                                .pruneIntervalDays()),
                new Reader(
                        "JkHttpConfig.port",
                        8910,
                        "[http]\nport = 1111\n",
                        1111,
                        "JK_HTTP_PORT",
                        "2222",
                        2222,
                        "70000",
                        (f, e) -> JkHttpConfig.resolve(f, e).orElseThrow().port()),
                new Reader(
                        "JkHistoryConfig.max-age-days",
                        30,
                        "[history]\nmax-age-days = 11\n",
                        11,
                        "JK_HISTORY_MAX_AGE_DAYS",
                        "22",
                        22,
                        "-1",
                        (f, e) -> JkHistoryConfig.resolve(f, e).maxAgeDays()),
                // No range rule: a boolean's decode is its validation. This row pins the order only.
                new Reader(
                        "JkM2Config.integration",
                        true,
                        "[m2]\nintegration = false\n",
                        false,
                        "JK_M2_INTEGRATION",
                        "true",
                        true,
                        null,
                        (f, e) -> JkM2Config.resolve(f, e).integration()),
                // No env layer at all: the same rule with one fewer rank.
                new Reader(
                        "JkTemplatesConfig.official",
                        JkTemplatesConfig.DEFAULT_OFFICIAL,
                        "[templates]\nofficial = \"https://git.example/t\"\n",
                        "https://git.example/t",
                        null,
                        null,
                        null,
                        null,
                        (f, e) -> JkTemplatesConfig.resolve(f).officialUrl()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readers")
    void built_in_when_no_layer_supplies_a_value(Reader r, @TempDir Path dir) throws IOException {
        assertThat(r.read().of(dir.resolve("absent.toml"), noEnv())).isEqualTo(r.builtIn());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readers")
    void the_file_beats_the_built_in(Reader r, @TempDir Path dir) throws IOException {
        assertThat(r.read().of(write(dir, r.fileBody()), noEnv())).isEqualTo(r.fromFile());
        assertThat(r.fromFile()).isNotEqualTo(r.builtIn());
    }

    /** Every reader that has an env layer — all but {@link JkTemplatesConfig}, which has none. */
    static List<Reader> readersWithEnv() {
        return readers().stream().filter(r -> r.envName() != null).toList();
    }

    /** Every reader whose field has a range rule, so the fall-through arm is observable. */
    static List<Reader> readersWithRange() {
        return readers().stream().filter(r -> r.invalidEnv() != null).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readersWithEnv")
    void the_env_beats_the_file(Reader r, @TempDir Path dir) throws IOException {
        Object got = r.read().of(write(dir, r.fileBody()), Map.of(r.envName(), r.validEnv())::get);
        assertThat(got).isEqualTo(r.fromEnv());
        assertThat(r.fromEnv()).isNotEqualTo(r.fromFile());
    }

    /**
     * The one row the table cannot make three-way distinct. A boolean has two values, so its env
     * value is necessarily either the file's or the built-in's — pin it both ways instead, which
     * rules out the same "read nothing, answer the default" pass the third value rules out
     * elsewhere.
     */
    @Test
    void a_two_valued_setting_still_ranks_env_over_file(@TempDir Path dir) throws IOException {
        Function<String, String> on = Map.of("JK_M2_INTEGRATION", "true")::get;
        Function<String, String> off = Map.of("JK_M2_INTEGRATION", "false")::get;
        assertThat(JkM2Config.resolve(write(dir, "[m2]\nintegration = false\n"), on)
                        .integration())
                .isTrue();
        assertThat(JkM2Config.resolve(write(dir, "[m2]\nintegration = true\n"), off)
                        .integration())
                .isFalse();
    }

    /**
     * The arm {@link MachineConfig} owns. An env value the setting's predicate rejects is not a
     * value: the file layer under it wins, and the command still runs. Before the fold this was
     * written out per field, twice per field, and nothing tied the two spellings together.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("readersWithRange")
    void an_invalid_high_layer_falls_through_to_the_one_below(Reader r, @TempDir Path dir) throws IOException {
        Object got = r.read().of(write(dir, r.fileBody()), Map.of(r.envName(), r.invalidEnv())::get);
        assertThat(got).isEqualTo(r.fromFile());
    }

    /**
     * The table's own size, so a row that quietly stops running is visible. Six readers; five have
     * an env layer; four carry a range rule, which is what makes the fall-through arm observable.
     */
    @Test
    void the_table_covers_all_six_readers() {
        assertThat(readers()).hasSize(6);
        assertThat(readersWithEnv()).hasSize(5);
        assertThat(readersWithRange()).hasSize(4);
        // Every range row must be three-way distinct, or "env won" and "nothing was read" are the
        // same observation and the fall-through arm below proves nothing.
        for (Reader r : readersWithRange()) {
            assertThat(List.of(r.builtIn(), r.fromFile(), r.fromEnv())).doesNotHaveDuplicates();
        }
    }

    private static Function<String, String> noEnv() {
        return name -> null;
    }

    private static Path write(Path dir, String body) throws IOException {
        Path f = dir.resolve("config.toml");
        Files.writeString(f, body);
        return f;
    }
}
