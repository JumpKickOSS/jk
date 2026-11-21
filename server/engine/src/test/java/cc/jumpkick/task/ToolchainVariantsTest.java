// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.task.ToolchainPolicy.Expectation;
import cc.jumpkick.task.ToolchainPolicy.Match;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One key, a record per JDK: with compile and test keys naming the Java level, records
 * two JDKs made under one key are variants, not a conflict; a lookup takes the closest one the
 * step's policy accepts, and none when no record is acceptable.
 */
class ToolchainVariantsTest {

    private static final String KEY = "a".repeat(64);
    private static final String TEMURIN_25_0_5 = "toolchain:jdk:eclipse-adoptium/25.0.5+11-LTS/aaaaaaaaaaaaaaaa";
    private static final String CORRETTO_25 = "toolchain:jdk:amazon.com-inc./25.0.3+9-LTS/cccccccccccccccc";
    private static final String OPENJDK_27 = "toolchain:jdk:oracle-corporation/27.0.1+9/dddddddddddddddd";

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void each_jdk_keeps_its_record_and_a_reader_takes_the_closest_it_accepts(@TempDir Path tmp) throws Exception {
        inRequest(() -> closest(tmp));
    }

    private static void closest(Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));

        ToolchainPolicy.expectForTests(KEY, new Expectation(CORRETTO_25, 21, Match.MAJOR, null));
        ac.storeVerdict("compile-main@x", KEY, Map.of("made", "corretto"));
        ToolchainPolicy.expectForTests(KEY, new Expectation(OPENJDK_27, 21, Match.MAJOR, null));
        ac.storeVerdict("compile-main@x", KEY, Map.of("made", "openjdk"));

        // The latest record is 27's; a reader on Temurin 25 takes the JDK 25 variant.
        ToolchainPolicy.expectForTests(KEY, new Expectation(TEMURIN_25_0_5, 21, Match.MAJOR, null));
        assertThat(ac.lookup(KEY).orElseThrow().meta().toolchain()).isEqualTo(CORRETTO_25);

        // A reader on 27 takes its own; at `level`, a Temurin 25 reader still prefers its major.
        ToolchainPolicy.expectForTests(KEY, new Expectation(OPENJDK_27, 21, Match.MAJOR, null));
        assertThat(ac.lookup(KEY).orElseThrow().meta().toolchain()).isEqualTo(OPENJDK_27);
        ToolchainPolicy.expectForTests(KEY, new Expectation(TEMURIN_25_0_5, 21, Match.LEVEL, null));
        assertThat(ac.lookup(KEY).orElseThrow().meta().toolchain()).isEqualTo(CORRETTO_25);

        // `build` takes neither: no record of this exact build.
        ToolchainPolicy.expectForTests(KEY, new Expectation(TEMURIN_25_0_5, 21, Match.BUILD, null));
        assertThat(ac.lookup(KEY)).isEmpty();
        // A reader on 21 for a level-17 module takes nothing made on 25 or 27 under `major`.
        String temurin21 = "toolchain:jdk:eclipse-adoptium/21.0.8+9-LTS/9999999999999999";
        ToolchainPolicy.expectForTests(KEY, new Expectation(temurin21, 17, Match.MAJOR, null));
        assertThat(ac.lookup(KEY)).isEmpty();
        assertThat(ToolchainChoice.note(OPENJDK_27, new Expectation(temurin21, 17, Match.MAJOR, null)))
                .isEqualTo("cached results here were made on JDK 27; this build uses 21:"
                        + " jk jdk install temurin-27, or set [cache] jdk-match = \"level\"");
    }

    @Test
    void a_record_from_below_the_level_is_never_taken(@TempDir Path tmp) throws Exception {
        inRequest(() -> belowLevel(tmp));
    }

    private static void belowLevel(Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        String key = "b".repeat(64);
        String temurin21 = "toolchain:jdk:eclipse-adoptium/21.0.8+9-LTS/9999999999999999";
        ToolchainPolicy.expectForTests(key, new Expectation(temurin21, 21, Match.LEVEL, null));
        ac.storeVerdict("run-tests@x", key, Map.of());
        ToolchainPolicy.expectForTests(key, new Expectation(OPENJDK_27, 25, Match.LEVEL, null));
        assertThat(ac.lookup(key)).as("a JDK 21 record for a level-25 suite").isEmpty();
    }

    @Test
    void a_variant_that_does_not_read_is_a_miss_and_goes_while_the_latest_record_stays(@TempDir Path tmp)
            throws Exception {
        inRequest(() -> tornVariant(tmp));
    }

    private static void tornVariant(Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Cas cas = new Cas(cache.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        ToolchainPolicy.expectForTests(KEY, new Expectation(CORRETTO_25, 21, Match.MAJOR, null));
        ac.storeVerdict("compile-main@x", KEY, Map.of("made", "corretto"));
        ToolchainPolicy.expectForTests(KEY, new Expectation(OPENJDK_27, 21, Match.MAJOR, null));
        ac.storeVerdict("compile-main@x", KEY, Map.of("made", "openjdk"));
        LocalCacheBackend local = new LocalCacheBackend(cas, cache.resolve("actions"), null);
        Path corretto = local.variantPath(KEY, CORRETTO_25);
        Files.writeString(corretto, "TASK compile-main@x\nOUTPUT not-a-record");

        ToolchainPolicy.expectForTests(KEY, new Expectation(TEMURIN_25_0_5, 21, Match.MAJOR, null));
        assertThat(ac.lookup(KEY)).as("the only JDK 25 record does not read").isEmpty();
        assertThat(corretto).doesNotExist();
        assertThat(local.recordPath(KEY)).exists();
        ToolchainPolicy.expectForTests(KEY, new Expectation(OPENJDK_27, 21, Match.MAJOR, null));
        assertThat(ac.lookup(KEY).orElseThrow().meta().toolchain()).isEqualTo(OPENJDK_27);
    }

    /** Expectations live for a request, as the steps of one build state and read them. */
    private static void inRequest(Request.Action<Exception> body) throws Exception {
        Request.of(1, new IoLedger()).run(() -> {
            try {
                body.run();
            } finally {
                RequestScope.release();
            }
        });
    }
}
