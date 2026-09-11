// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.AffectedChanged;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ProtoSession#sessionOf} is the one request-to-{@link Session} constructor, so a build and
 * the explain that forecasts it derive the same session from the same knobs. Every explain-versus-
 * build divergence so far was a session that differed in one field; this pins the class, not the
 * instance.
 */
class ProtoSessionRequestTest {

    private static final String DIR = "/w/app";
    private static final String CACHE = "/w/cache";
    private static final String JDKS = "/w/jdks";
    private static final TestSelection SELECTION = TestSelection.DEFAULT.withIncludeTags(List.of("integration"));
    private static final PluginTuning TUNING = new PluginTuning(60.0, "G1", null, List.of("-Xss2m"));

    private static final Session.CancelToken TOKEN = Session.CancelToken.live();
    private static final AffectedChanged CHANGED = new AffectedChanged();
    private static final IoLedger IO = new IoLedger();

    /** The envelope the client splices on every job line; explain carries {@code rebuild} itself. */
    private static String enveloped(String line, boolean rebuild) {
        return ProtoSession.withToolchain(
                ProtoSession.withSession(line, "release", Map.of("SIGNING_KEY", "k"), TUNING, rebuild),
                "temurin-21",
                "graal-25",
                "/opt/graal-25");
    }

    /**
     * Cancellation, the changed-type carrier and the byte ledger are per-run identities, not
     * request facts; pin them so {@code equals} compares the facts.
     */
    private static Session facts(String line) {
        return ProtoSession.sessionOf(line, TOKEN).withAffectedChanged(CHANGED).withIo(IO);
    }

    private static String buildLine() {
        return enveloped(
                new BuildRequest(
                                DIR, CACHE, JDKS, 3, "ci", false, true, 4, false, false, false, false, false, false,
                                null, SELECTION, null, List.of(), false, null, Map.of(), null, null, null)
                        .encode(),
                true);
    }

    private static String explainLine() {
        return enveloped(
                new ExplainRequest(DIR, CACHE, 3, false, "ci", JDKS, false, false, true, true, 4, SELECTION).encode(),
                false);
    }

    private static String testLine() {
        return enveloped(
                new TestRequest(DIR, CACHE, JDKS, 3, "ci", true, false, false, false, SELECTION, null, null, null)
                        .encode(),
                true);
    }

    @Test
    void build_and_explain_derive_the_same_session_from_the_same_knobs() {
        assertThat(facts(explainLine())).isEqualTo(facts(buildLine()));
    }

    @Test
    void build_and_test_derive_the_same_session_from_the_same_knobs() {
        assertThat(facts(testLine())).isEqualTo(facts(buildLine()));
    }

    /** Equal sessions prove nothing if both are empty; the knobs have to have landed. */
    @Test
    void the_session_carries_every_knob_the_line_does() {
        Session s = ProtoSession.sessionOf(buildLine(), TOKEN);
        assertThat(s.workingDir()).isEqualTo(Path.of(DIR));
        assertThat(s.cacheDir()).isEqualTo(Path.of(CACHE));
        assertThat(s.jdksDir()).isEqualTo(Path.of(JDKS));
        assertThat(s.requestedTestWorkers()).isEqualTo(3);
        assertThat(s.parallelTests()).isFalse();
        assertThat(s.verbose()).isTrue();
        assertThat(s.config().rebuildOr(false)).isTrue();
        assertThat(s.testSelection()).isEqualTo(SELECTION);
        assertThat(s.variant()).isEqualTo("release");
        assertThat(s.clientEnv()).containsEntry("SIGNING_KEY", "k");
        assertThat(s.jvm()).isEqualTo(TUNING);
        assertThat(s.jdkSpec()).isEqualTo("temurin-21");
        assertThat(s.graalSpec()).isEqualTo("graal-25");
        assertThat(s.graalHome()).isEqualTo(Path.of("/opt/graal-25"));
        assertThat(s.cancel()).isSameAs(TOKEN);
    }

    /** A test or build line asking for a debugger lands the listener on the session; a silent line leaves it null. */
    @Test
    void a_debug_listener_on_the_line_lands_on_the_session() {
        DebugJvm debug = DebugJvm.parse("*:6006,suspend=n");
        String test = new TestRequest(
                        DIR, CACHE, JDKS, 3, "ci", true, false, false, false, SELECTION, debug.spelling(), null, null)
                .encode();
        String build = new BuildRequest(
                        DIR,
                        CACHE,
                        JDKS,
                        3,
                        "ci",
                        false,
                        true,
                        4,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        SELECTION,
                        debug.spelling(),
                        List.of(),
                        false,
                        null,
                        Map.of(),
                        null,
                        null,
                        null)
                .encode();

        assertThat(ProtoSession.sessionOf(test, TOKEN).debugJvm()).isEqualTo(debug);
        assertThat(ProtoSession.sessionOf(build, TOKEN).debugJvm()).isEqualTo(debug);
        assertThat(ProtoSession.sessionOf(testLine(), TOKEN).debugJvm()).isNull();
        assertThat(TestRequest.decode(test).debugJvm()).isEqualTo(debug.spelling());
        assertThat(testLine()).doesNotContain("debugJvm");
    }

    /** The forecast's {@code --guard} is the selection's guard flag, read the same way as a build's. */
    @Test
    void a_forecast_guard_flag_lands_on_the_selection() {
        String line = new ForecastRequest(DIR, CACHE, false, false, false, false, true).encode();
        assertThat(ProtoSession.sessionOf(line, TOKEN).testSelection().guard()).isTrue();
    }

    @Test
    void a_request_without_a_cache_field_falls_back_to_the_engine_cache() {
        // A read-only request may carry no cache path; that means "the engine's own", not null.
        String request = new ProjectInfoRequest("/tmp/whatever", null, null, false, false).encode();
        assertThat(request).doesNotContain("\"cache\"");
        assertThat(ProtoSession.sessionOf(request, TOKEN).cacheDir()).isEqualTo(JkDirs.cache());
    }

    @Test
    void a_request_that_carries_a_cache_still_wins() {
        String request = new OutdatedRequest("/tmp/whatever", "/tmp/cachedir", null, false, false).encode();
        assertThat(ProtoSession.sessionOf(request, TOKEN).cacheDir()).isEqualTo(Path.of("/tmp/cachedir"));
    }
}
