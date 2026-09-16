// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jsonl.Jsonl;
import org.junit.jupiter.api.Test;

/**
 * The three wire splicers share one policy, because they share one implementation
 * ({@code Jsonl.append}) —.
 *
 * <p>Before, {@code withSession} and {@code withOrigin} threw on a line that was not an encoded
 * object and {@code withCancelled} returned it unchanged, so the same mistake was loud on the
 * request path and silent on the event path. All three also produced the unparseable
 * {@code {,"k":v}} from an empty object, each having written its own constant comma.
 *
 * <p>These assertions go through the wire text and then back through the readers: a splice that
 * produced plausible-looking bytes and did not parse would pass a {@code contains} check alone.
 */
class WireSpliceTest {

    @Test
    void every_splicer_rejects_a_line_that_is_not_an_encoded_object() {
        assertThatThrownBy(() -> ProtoEvents.withCancelled("not-json", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoSession.withOrigin("not-json", "web", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoSession.withSession("not-json", "release", null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        // Same answer for the shapes that used to slip through the lenient one: a null line, and a
        // line whose last `}` is not its last character.
        assertThatThrownBy(() -> ProtoEvents.withCancelled(null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoEvents.withCancelled("{\"a\":1} trailing", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void every_splicer_produces_parseable_json_from_an_empty_object() {
        assertThat(ProtoEvents.withCancelled("{}", true)).isEqualTo("{\"cancelled\":true}");
        assertThat(ProtoSession.withOrigin("{}", "web", null)).isEqualTo("{\"trigger\":\"web\"}");
        assertThat(ProtoSession.withSession("{}", null, null, null, true)).isEqualTo("{\"rebuild\":true}");

        assertThat(Jsonl.bool(ProtoEvents.withCancelled("{}", true), "cancelled", false))
                .isTrue();
        assertThat(Jsonl.str(ProtoSession.withOrigin("{}", "web", null), "trigger"))
                .isEqualTo("web");
        assertThat(Jsonl.bool(ProtoSession.withSession("{}", null, null, null, true), "rebuild", false))
                .isTrue();
    }

    @Test
    void a_splice_with_nothing_to_add_is_byte_identical() {
        String base = ProtoLifecycle.ping();
        assertThat(ProtoSession.withOrigin(base, null, null)).isEqualTo(base);
        assertThat(ProtoSession.withOrigin(base, "  ", null)).isEqualTo(base);
        assertThat(ProtoSession.withSession(base, null, null, null, false)).isEqualTo(base);
    }

    /**
     * The production combination, from {@code EngineListeners.encodePlanFinish}: the overload that
     * carries test counts is the one that does <em>not</em> write {@code cancelled} itself, so the
     * splice is what adds it. The two-argument {@code planFinish} already writes the field and is
     * never spliced — {@code Jsonl.append} is a splicer, not a setter, and appending a key the
     * object already has would leave two of them with the readers taking the first.
     */
    @Test
    void a_cancelled_flag_rides_a_real_plan_finish() {
        String base = ProtoEvents.planFinish("/w", false, 7, 4, 2, 1);
        assertThat(base).doesNotContain("\"cancelled\"");

        String line = ProtoEvents.withCancelled(base, true);

        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.str(line, "dir")).isEqualTo("/w");
        assertThat(Jsonl.bool(line, "success", true)).isFalse();
        assertThat(Jsonl.bool(line, "cancelled", false)).isTrue();
        assertThat(line.indexOf("\"cancelled\"")).isEqualTo(line.lastIndexOf("\"cancelled\""));
    }
}
