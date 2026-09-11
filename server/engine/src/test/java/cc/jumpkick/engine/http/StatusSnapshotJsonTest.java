// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link StatusSnapshot#toJson} is the serializer for engine vitals.
 *
 * <p>Before this, {@code GET /api/status} and the dashboard's SSE {@code status} frame each built
 * the same nineteen-field object from the same accessors, nineteen lines apart in two files. They
 * happened to agree; nothing made them. A field added to the record reached whichever surface the
 * author remembered.
 *
 * <p>Two surfaces still do not go through the owner and are outside this ticket's file scope. Both
 * are measured here rather than described, because "MCP drops some fields" was filed as five and is
 * eight:
 *
 * <ul>
 *   <li>{@code McpVitals.statusPayload} — the MCP {@code status} envelope, 10 of the record's 18
 *       components. Omits {@code aotTrainingPid}, {@code totalMemoryBytes},
 *       {@code availableMemoryBytes}, {@code systemCpuLoad}, {@code systemLoadAverage},
 *       {@code engineEpoch}, {@code peakActiveRequests}, {@code peakActiveBuildPlans} — so an agent
 *       reading {@code jk_status} cannot see host memory headroom, host load, or the epoch that
 *       tells it the engine was replaced under it.
 *   <li>{@code ProtoLifecycle.statusAck}, fed positionally by {@code EngineServer} — the CLI socket
 *       {@code status-ack}, 12 of 18. Omits {@code cores}, {@code totalMemoryBytes},
 *       {@code availableMemoryBytes}, {@code systemCpuLoad}, {@code systemLoadAverage},
 *       {@code engineEpoch}, and adds five socket-only fields ({@code proto}, {@code draining},
 *       {@code httpUrl}, {@code httpError}, {@code mcpUrl}).
 * </ul>
 */
class StatusSnapshotJsonTest {

    private static final StatusSnapshot SNAPSHOT = new StatusSnapshot(
            "9.9.9-test",
            42L,
            1_000L,
            3,
            2,
            1_000L,
            2_000L,
            3_000L,
            4_000L,
            77L,
            8,
            16_000_000_000L,
            8_000_000_000L,
            0.18,
            1.2,
            "9.9.9-test@1000",
            5,
            4,
            6L,
            7_000L,
            8_000L);

    /**
     * The one wire name that is not its component name. Every other field is serialized under the
     * accessor's own spelling, which is what lets the check below be exhaustive instead of a list.
     */
    private static final Map<String, String> RENAMED = Map.of("startedAtMillis", "startedAt");

    /**
     * Every component reaches the wire. This is the assertion the two duplicated builders could not
     * make: with one serializer, a field added to the record and forgotten here fails immediately,
     * where before it silently reached one surface and not the other.
     */
    @Test
    void to_json_serializes_every_component_of_the_record() {
        String body = SNAPSHOT.toJson().toString();

        List<String> missing = new ArrayList<>();
        for (RecordComponent c : StatusSnapshot.class.getRecordComponents()) {
            String wireName = RENAMED.getOrDefault(c.getName(), c.getName());
            if (!body.contains("\"" + wireName + "\":")) missing.add(c.getName());
        }

        assertThat(StatusSnapshot.class.getRecordComponents())
                .as("the record's components — an empty reflection result would pass the loop above")
                .hasSizeGreaterThanOrEqualTo(18);
        assertThat(missing)
                .as("StatusSnapshot components that toJson() does not emit: %s", body)
                .isEmpty();
        // Derived, not stored: a reader that computed it would be reading a second clock.
        assertThat(body).contains("\"uptimeSeconds\":");
    }

    /** Every component is a key of {@code vitals()} — the check the two other surfaces inherit by starting from it. */
    @Test
    void vitals_carries_every_component_of_the_record() {
        Map<String, Object> vitals = SNAPSHOT.vitals();
        List<String> missing = new ArrayList<>();
        for (RecordComponent c : StatusSnapshot.class.getRecordComponents()) {
            String wireName = RENAMED.getOrDefault(c.getName(), c.getName());
            if (!vitals.containsKey(wireName)) missing.add(c.getName());
        }
        assertThat(missing).as("StatusSnapshot components that vitals() omits").isEmpty();
        assertThat(vitals).containsKey("uptimeSeconds");
        assertThat(vitals.keySet().iterator().next())
                .as("version leads, as every surface renders it")
                .isEqualTo("version");
    }

    /** Values, not just keys — a serializer that emitted every name and the wrong number passes a key check. */
    @Test
    void to_json_carries_the_snapshots_own_values() {
        String body = SNAPSHOT.toJson().toString();

        assertThat(body)
                .contains("\"version\":\"9.9.9-test\"")
                .contains("\"pid\":42")
                .contains("\"startedAt\":1000")
                .contains("\"activeRequests\":3")
                .contains("\"activeBuildPlans\":2")
                .contains("\"peakActiveRequests\":5")
                .contains("\"peakActiveBuildPlans\":4")
                .contains("\"idleDropped\":6")
                .contains("\"logBytes\":7000")
                .contains("\"logRolledAt\":8000")
                .contains("\"rssBytes\":4000")
                .contains("\"aotTrainingPid\":77")
                .contains("\"cores\":8")
                .contains("\"availableMemoryBytes\":8000000000")
                .contains("\"systemCpuLoad\":0.18")
                .contains("\"systemLoadAverage\":1.2")
                .contains("\"engineEpoch\":\"9.9.9-test@1000\"");
    }

    /**
     * The ownership arm, in the shape the campaign uses for closure: a tree scan, not an assertion
     * about the file this test happens to know. A production file "renders vitals" when it reads
     * four or more of the record's accessors <em>and</em> writes JSON keys — the second half is what
     * keeps {@code LiveVitals.PresentStatus}, which reads thirteen accessors to build a change-gate
     * fingerprint and serializes nothing, out of the result.
     *
     * <p>The expectation is empty: every renderer starts from {@code vitals()} and reads no accessor
     * of its own.
     */
    @Test
    void no_file_renders_engine_vitals_without_the_owner() throws IOException {
        Path main = RepoRoot.dir(StatusSnapshotJsonTest.class, "server/engine/src/main/java");
        Set<String> accessors = new LinkedHashSet<>();
        for (RecordComponent c : StatusSnapshot.class.getRecordComponents()) {
            accessors.add("." + c.getName() + "()");
        }

        List<Path> scanned = new ArrayList<>();
        Set<String> renderers = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(main)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                if (!f.getFileName().toString().endsWith(".java")) continue;
                scanned.add(f);
                String body = Files.readString(f, StandardCharsets.UTF_8);
                long reads = accessors.stream().filter(body::contains).count();
                boolean writesJson = body.contains("\\\":") || body.contains(".put(\"");
                if (reads >= 4 && writesJson) {
                    renderers.add(main.relativize(f).toString().replace('\\', '/'));
                }
            }
        }

        assertThat(scanned)
                .as("engine production sources scanned under %s", main)
                .hasSizeGreaterThan(200);
        assertThat(renderers)
                .as("files rendering StatusSnapshot as JSON without StatusSnapshot.vitals()")
                .isEmpty();
    }
}
