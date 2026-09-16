// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.build.InvocationPhase;
import cc.jumpkick.run.TestSummary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EngineProtocolTest {

    @Test
    void hello_round_trips_the_version() {
        String json = ProtoLifecycle.hello("1.2.3");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO);
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
    }

    @Test
    void hello_ack_round_trips_version_pid_start_time_and_build_id() {
        String json = ProtoLifecycle.helloAck("1.2.3", 4321, 999_000, true, "abc123def456");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO_ACK);
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Jsonl.longValue(json, "pid", -1)).isEqualTo(4321);
        assertThat(Jsonl.longValue(json, "startedAt", -1)).isEqualTo(999_000);
        assertThat(Jsonl.bool(json, "draining", false)).isTrue();
        assertThat(Jsonl.str(json, "buildId")).isEqualTo("abc123def456");
        // Identity-less contexts answer an EMPTY buildId ("no opinion"), never null.
        assertThat(Jsonl.str(ProtoLifecycle.helloAck("1.2.3", 1, 1, false, null), "buildId"))
                .isEmpty();
    }

    @Test
    void ping_and_pong_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(ProtoLifecycle.ping())).isEqualTo(EngineProtocol.PING);
        assertThat(EngineProtocol.typeOf(ProtoLifecycle.pong())).isEqualTo(EngineProtocol.PONG);
    }

    /** Vitals in the engine's own order; the socket-only keys follow them. */
    private static Map<String, Object> vitals(int activeRequests, int activeBuildPlans) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", "1.2.3");
        v.put("pid", 42L);
        v.put("startedAt", 1_000L);
        v.put("activeRequests", activeRequests);
        v.put("activeBuildPlans", activeBuildPlans);
        v.put("heapUsedBytes", 18_000_000L);
        v.put("heapCommittedBytes", 42_000_000L);
        v.put("heapMaxBytes", 268_435_456L);
        v.put("rssBytes", -1L);
        v.put("aotTrainingPid", -1L);
        v.put("cores", 8);
        v.put("totalMemoryBytes", 16_000_000_000L);
        v.put("availableMemoryBytes", 8_000_000_000L);
        v.put("systemCpuLoad", 0.18);
        v.put("systemLoadAverage", 1.2);
        v.put("engineEpoch", "1.2.3@1000");
        return v;
    }

    @Test
    void status_ack_carries_every_vital_and_the_socket_facts() {
        String json = ProtoLifecycle.statusAck(vitals(3, 7), true, null, null, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.STATUS_ACK);
        assertThat(json).startsWith("{\"" + EngineProtocol.TYPE_FIELD + "\":");
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Jsonl.longValue(json, "pid", -1)).isEqualTo(42);
        assertThat(Jsonl.longValue(json, "startedAt", -1)).isEqualTo(1_000);
        assertThat(Jsonl.intValue(json, "proto", -1)).isEqualTo(EngineProtocol.PROTOCOL);
        assertThat(Jsonl.intValue(json, "activeRequests", -99)).isEqualTo(3);
        assertThat(Jsonl.intValue(json, "activeBuildPlans", -99)).isEqualTo(7);
        assertThat(Jsonl.bool(json, "draining", false)).isTrue();
        assertThat(Jsonl.longValue(json, "heapUsedBytes", -99)).isEqualTo(18_000_000);
        assertThat(Jsonl.longValue(json, "heapCommittedBytes", -99)).isEqualTo(42_000_000);
        assertThat(Jsonl.longValue(json, "heapMaxBytes", -99)).isEqualTo(268_435_456);
        assertThat(Jsonl.longValue(json, "rssBytes", -99)).isEqualTo(-1); // -1 = unobservable
        assertThat(Jsonl.longValue(json, "aotTrainingPid", -99)).isEqualTo(-1); // -1 = no trainer running
        // The six that a positional parameter list left behind.
        assertThat(Jsonl.intValue(json, "cores", -1)).isEqualTo(8);
        assertThat(Jsonl.longValue(json, "totalMemoryBytes", -1)).isEqualTo(16_000_000_000L);
        assertThat(Jsonl.longValue(json, "availableMemoryBytes", -1)).isEqualTo(8_000_000_000L);
        assertThat(Jsonl.doubleValue(json, "systemCpuLoad", -1)).isEqualTo(0.18);
        assertThat(Jsonl.doubleValue(json, "systemLoadAverage", -1)).isEqualTo(1.2);
        assertThat(Jsonl.str(json, "engineEpoch")).isEqualTo("1.2.3@1000");
        assertThat(Jsonl.str(json, "httpUrl")).isNull(); // null when http is off
        assertThat(Jsonl.str(json, "httpError")).isNull();
        assertThat(Jsonl.str(json, "mcpUrl")).isNull();
    }

    @Test
    void status_ack_carries_http_url_when_serving() {
        String json = ProtoLifecycle.statusAck(vitals(3, 0), false, "http://127.0.0.1:8910/", null, true);
        assertThat(Jsonl.str(json, "httpUrl")).isEqualTo("http://127.0.0.1:8910/");
        assertThat(Jsonl.str(json, "httpError")).isNull();
        // Trailing slash on httpUrl must not produce //mcp
        assertThat(Jsonl.str(json, "mcpUrl")).isEqualTo("http://127.0.0.1:8910/mcp");
    }

    @Test
    void status_ack_omits_mcp_url_when_mcp_disabled() {
        String json = ProtoLifecycle.statusAck(vitals(3, 0), false, "http://127.0.0.1:8910/", null, false);
        assertThat(Jsonl.str(json, "httpUrl")).isEqualTo("http://127.0.0.1:8910/");
        assertThat(Jsonl.str(json, "mcpUrl")).isNull();
    }

    @Test
    void mcp_url_strips_trailing_slashes() {
        assertThat(ProtoLifecycle.mcpUrlFromHttp("http://127.0.0.1:8910/")).isEqualTo("http://127.0.0.1:8910/mcp");
        assertThat(ProtoLifecycle.mcpUrlFromHttp("http://127.0.0.1:8910")).isEqualTo("http://127.0.0.1:8910/mcp");
        assertThat(ProtoLifecycle.mcpUrlFromHttp(null)).isNull();
    }

    @Test
    void status_ack_carries_http_error_when_bind_failed() {
        String json = ProtoLifecycle.statusAck(vitals(3, 0), false, null, "Address already in use", true);
        assertThat(Jsonl.str(json, "httpUrl")).isNull();
        assertThat(Jsonl.str(json, "httpError")).isEqualTo("Address already in use");
    }

    @Test
    void shutdown_and_bye_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(ProtoLifecycle.shutdown())).isEqualTo(EngineProtocol.SHUTDOWN);
        assertThat(EngineProtocol.typeOf(ProtoLifecycle.bye())).isEqualTo(EngineProtocol.BYE);
    }

    @Test
    void shutdown_carries_force_flag_and_defaults_false() {
        assertThat(Jsonl.bool(ProtoLifecycle.shutdown(), "force", true)).isFalse();
        assertThat(Jsonl.bool(ProtoLifecycle.shutdown(false), "force", true)).isFalse();
        assertThat(Jsonl.bool(ProtoLifecycle.shutdown(true), "force", false)).isTrue();
    }

    @Test
    void drain_status_and_done_round_trip() {
        String status = ProtoLifecycle.drainStatus(99, 3, "1.2.3");
        assertThat(EngineProtocol.typeOf(status)).isEqualTo(EngineProtocol.DRAIN_STATUS);
        assertThat(Jsonl.longValue(status, "pid", -1)).isEqualTo(99);
        assertThat(Jsonl.intValue(status, "plans", -1)).isEqualTo(3);
        assertThat(Jsonl.str(status, "version")).isEqualTo("1.2.3");
        String done = ProtoLifecycle.drainDone(99);
        assertThat(EngineProtocol.typeOf(done)).isEqualTo(EngineProtocol.DRAIN_DONE);
        assertThat(Jsonl.longValue(done, "pid", -1)).isEqualTo(99);
    }

    @Test
    void bye_reports_in_flight_jobs_and_draining() {
        String bye = ProtoLifecycle.bye(3, true);
        assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        assertThat(Jsonl.intValue(bye, "plans", -1)).isEqualTo(3);
        assertThat(Jsonl.bool(bye, "draining", false)).isTrue();
        // no-arg bye: 0 jobs, not draining
        assertThat(Jsonl.intValue(ProtoLifecycle.bye(), "plans", -1)).isEqualTo(0);
        assertThat(Jsonl.bool(ProtoLifecycle.bye(), "draining", true)).isFalse();
    }

    @Test
    void with_session_attaches_variant_env_and_jvm_in_one_validated_splice() {
        String base = ProtoLifecycle.ping();
        // Empty envelope: byte-identical.
        assertThat(ProtoSession.withSession(base, null, Map.of(), null)).isEqualTo(base);
        // Variant + env ride every hosted request; env is the one flat-map encoding.
        String line = ProtoSession.withSession(base, "release|tier=free", Map.of("KEY_PASS", "s3cret"), null);
        assertThat(ProtoSession.variantOf(line)).isEqualTo("release|tier=free");
        assertThat(ProtoSession.clientEnvOf(line)).containsExactly(Map.entry("KEY_PASS", "s3cret"));
        // A non-encoded line is rejected, not silently mangled.
        assertThatThrownBy(() -> ProtoSession.withSession("not-json", "release", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void with_session_carries_assembly_override() {
        String base = ProtoLifecycle.ping();
        assertThat(ProtoSession.assemblyOverrideOf(base)).isEmpty();
        String line = ProtoSession.withSession(base, null, null, null, false, false, "minified");
        assertThat(ProtoSession.assemblyOverrideOf(line)).isEqualTo("minified");
        String fat = ProtoSession.withSession(base, null, null, null, false, false, "fat");
        assertThat(ProtoSession.assemblyOverrideOf(fat)).isEqualTo("fat");
    }

    @Test
    void goal_finish_carries_its_kind_discriminator() {
        assertThat(Jsonl.str(ProtoEvents.planFinish("/w", true), "kind")).isEqualTo("build");
        assertThat(Jsonl.str(ProtoEvents.planFinishSync("/w", true, 3, 4), "kind"))
                .isEqualTo("sync");
        assertThat(Jsonl.str(ProtoEvents.planFinishLock("/w", true, 1, 2, 3, 0, List.of()), "kind"))
                .isEqualTo("lock");
    }

    @Test
    void the_error_envelope_carries_a_code_and_message() {
        String e = ProtoLifecycle.error(EngineProtocol.ERR_SHUTTING_DOWN, "draining — retry");
        assertThat(EngineProtocol.typeOf(e)).isEqualTo(EngineProtocol.ERROR);
        assertThat(Jsonl.str(e, "code")).isEqualTo(EngineProtocol.ERR_SHUTTING_DOWN);
        assertThat(Jsonl.str(e, "message")).isEqualTo("draining — retry");
    }

    @Test
    void type_of_malformed_json_is_null() {
        assertThat(EngineProtocol.typeOf("not json")).isNull();
    }

    @Test
    void build_request_carries_freshen_lock() {
        String on = buildRequest(true, false, null);
        assertThat(Jsonl.bool(on, "freshenLock", false)).isTrue();
        String off = buildRequest(false, false, null);
        assertThat(Jsonl.bool(off, "freshenLock", true)).isFalse();
        // rebuild rides the session envelope, not the builder.
        assertThat(Jsonl.bool(ProtoSession.withSession(off, null, null, null, true), "rebuild", false))
                .isTrue();
    }

    /**
     * Invocation-phase wire names come from the enum, and every enum value's wire name
     * round-trips through {@code fromWire}.
     */
    @Test
    void invocation_phase_wire_names_round_trip_the_enum() {
        for (var p : InvocationPhase.values()) {
            assertThat(InvocationPhase.fromWire(p.wireName())).isEqualTo(p);
        }
        String line = ProtoEvents.invocationPhase(InvocationPhase.RESOLVE.wireName(), "start");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.INVOCATION_PHASE);
        assertThat(Jsonl.str(line, "phase")).isEqualTo("resolve");
        assertThat(Jsonl.str(line, "status")).isEqualTo("start");
    }

    @Test
    void build_request_carries_test_only_and_dirty_hint() {
        String on = buildRequest(true, true, List.of("/w/api", "/w/core"));
        assertThat(Jsonl.bool(on, "testOnly", false)).isTrue();
        assertThat(BuildRequest.decode(on).dirtyHint()).containsExactly("/w/api", "/w/core");

        // Unset controls stay off the wire entirely.
        String off = buildRequest(true, false, null);
        assertThat(off).doesNotContain("testOnly").doesNotContain("dirtyHint");
        assertThat(BuildRequest.decode(off).dirtyHint()).isNull();

        // An empty selection is not a selection.
        String empty = buildRequest(true, false, List.of());
        assertThat(BuildRequest.decode(empty).dirtyHint()).isNull();
    }

    @Test
    void lock_request_round_trips_all_fields() {
        String json = new LockRequest(
                        "/work", "/cache", List.of("a", "b"), true, true, "http://repo", true, false, true, true)
                .encode();
        assertThat(LockRequest.decode(json))
                .isEqualTo(new LockRequest(
                        "/work", "/cache", List.of("a", "b"), true, true, "http://repo", true, false, true, true));
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.strArray(json, "features")).containsExactly("a", "b");
        assertThat(Jsonl.bool(json, "noDefaultFeatures", false)).isTrue();
        assertThat(Jsonl.bool(json, "sources", false)).isTrue();
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", true)).isFalse();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();
        assertThat(Jsonl.bool(json, "freshen", false)).isTrue();
    }

    @Test
    void lock_request_null_repo_url_decodes_as_absent() {
        String json = new LockRequest("/w", "/c", List.of(), false, false, null, false, false, false, false).encode();
        assertThat(Jsonl.str(json, "repoUrl")).isNull();
    }

    @Test
    void update_request_round_trips_the_git_splice_fields() {
        String json = new UpdateRequest(
                        "/work", "/cache", List.of(), false, null, true, "mylib", false, true, false, "", List.of(),
                        false, false)
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.UPDATE_REQUEST);
        assertThat(Jsonl.bool(json, "gitOnly", false)).isTrue();
        assertThat(Jsonl.str(json, "gitTarget")).isEqualTo("mylib");
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
    }

    @Test
    void update_request_round_trips_the_pin_rewrite_fields() {
        UpdateRequest req = new UpdateRequest(
                "/work",
                "/cache",
                List.of(),
                false,
                null,
                false,
                null,
                false,
                false,
                false,
                "",
                List.of("jackson", "com.acme:other"),
                true,
                true);
        UpdateRequest back = UpdateRequest.decode(req.encode());
        assertThat(back.deps()).containsExactly("jackson", "com.acme:other");
        assertThat(back.major()).isTrue();
        assertThat(back.preview()).isTrue();
        // Absent fields read as "every pin, same major, write".
        UpdateRequest bare = UpdateRequest.decode(RequestJson.request(EngineProtocol.UPDATE_REQUEST)
                .string("platform", "")
                .finish());
        assertThat(bare.deps()).isEmpty();
        assertThat(bare.major()).isFalse();
        assertThat(bare.preview()).isFalse();
    }

    @Test
    void update_rewrite_event_round_trips() {
        String line = ProtoEvents.updateRewrite(
                "/work/lib", "dependencies", "jackson", "com.acme:jackson", "2.18.0", "2.18.2");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.UPDATE_REWRITE);
        UpdateRewriteEvent e = UpdateRewriteEvent.decode(line);
        assertThat(e.dir()).isEqualTo("/work/lib");
        assertThat(e.table()).isEqualTo("dependencies");
        assertThat(e.handle()).isEqualTo("jackson");
        assertThat(e.module()).isEqualTo("com.acme:jackson");
        assertThat(e.from()).isEqualTo("2.18.0");
        assertThat(e.to()).isEqualTo("2.18.2");
    }

    @Test
    void sync_request_round_trips_all_fields() {
        String json =
                new SyncRequest("/work", "/cache", "/jdks", "http://repo", true, false, true, true, false).encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.SYNC_REQUEST);
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.bool(json, "sources", false)).isTrue();
        assertThat(Jsonl.bool(json, "offline", true)).isFalse();
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
        assertThat(Jsonl.bool(json, "refresh", false)).isTrue();
    }

    @Test
    void lock_module_and_package_events_round_trip() {
        String module = ProtoEvents.lockModule("/work/api", "com.example:api");
        assertThat(EngineProtocol.typeOf(module)).isEqualTo(EngineProtocol.LOCK_MODULE);
        assertThat(Jsonl.str(module, "dir")).isEqualTo("/work/api");
        assertThat(Jsonl.str(module, "coord")).isEqualTo("com.example:api");

        String pkg = ProtoEvents.lockPackage("/work/api", "com.foo:leaf", "1.0");
        assertThat(EngineProtocol.typeOf(pkg)).isEqualTo(EngineProtocol.LOCK_PACKAGE);
        assertThat(Jsonl.str(pkg, "name")).isEqualTo("com.foo:leaf");
        assertThat(Jsonl.str(pkg, "version")).isEqualTo("1.0");
    }

    @Test
    void goal_finish_lock_variant_carries_the_lockfile_counts() {
        String json = ProtoEvents.planFinishLock("", true, 13, 2, 1, 4, List.of("mirror"));
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.bool(json, "success", false)).isTrue();
        assertThat(Jsonl.longValue(json, "lockPackages", -1)).isEqualTo(13);
        assertThat(Jsonl.longValue(json, "lockSources", -1)).isEqualTo(2);
        assertThat(Jsonl.longValue(json, "lockPlugins", -1)).isEqualTo(1);
        PlanFinishLockEvent decoded = PlanFinishLockEvent.decode(json);
        assertThat(decoded.unverified()).isEqualTo(4);
        assertThat(decoded.insecureRepos()).containsExactly("mirror");
    }

    @Test
    void goal_finish_sync_variant_carries_the_summary_counts() {
        String json = ProtoEvents.planFinishSync("", true, 7, 42);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.longValue(json, "syncFetched", -1)).isEqualTo(7);
        assertThat(Jsonl.longValue(json, "syncUpToDate", -1)).isEqualTo(42);
    }

    @Test
    void lock_finish_round_trips_outcome_errors_and_refreshed_count() {
        String json = ProtoEvents.lockFinish(false, 6, List.of("boom", "again"), 3);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_FINISH);
        assertThat(Jsonl.bool(json, "success", true)).isFalse();
        assertThat(Jsonl.intValue(json, "exitCode", -1)).isEqualTo(6);
        assertThat(Jsonl.strArray(json, "errors")).containsExactly("boom", "again");
        assertThat(Jsonl.intValue(json, "refreshed", -1)).isEqualTo(3);
    }

    // ---- Wave 2: hosted worker commands ------------------------------------------------------------

    @Test
    void audit_request_round_trips_all_fields() {
        String json =
                new AuditRequest("/work", "/cache", "HIGH", "http://osv/batch", "http://osv/vulns/", true).encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Jsonl.str(json, "osvBatchUrl")).isEqualTo("http://osv/batch");
        assertThat(Jsonl.str(json, "osvVulnsUrl")).isEqualTo("http://osv/vulns/");
        // The audit worker queries OSV, so the run's offline decision has to ride the request.
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        // null overrides decode as absent (the real OSV endpoints)
        assertThat(Jsonl.str(new AuditRequest("/w", "/c", "LOW", null, null, false).encode(), "osvBatchUrl"))
                .isNull();
    }

    @Test
    void audit_finding_event_round_trips_the_worker_fields_and_the_ignore_state() {
        AuditReport.Finding judged = new AuditReport.Finding(
                "com.foo:leaf",
                "1.0",
                "GHSA-x",
                "bad news",
                AuditReport.Severity.HIGH,
                "1.1",
                new AuditReport.Ignore("not reachable", LocalDate.of(2027, 1, 31), false));
        String json = ProtoEvents.auditFinding("", judged);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_FINDING);
        assertThat(Jsonl.str(json, "package")).isEqualTo("com.foo:leaf");
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.0");
        assertThat(Jsonl.str(json, "id")).isEqualTo("GHSA-x");
        assertThat(Jsonl.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Jsonl.str(json, "summary")).isEqualTo("bad news");
        assertThat(Jsonl.str(json, "fixedIn")).isEqualTo("1.1");
        assertThat(AuditFindingEvent.decode(json).toFinding()).isEqualTo(judged);

        // A finding nothing in the manifest names decodes with no ignore at all, not an empty one.
        AuditReport.Finding bare = new AuditReport.Finding("g:a", "1", "CVE-1", "", AuditReport.Severity.LOW, null);
        assertThat(AuditFindingEvent.decode(ProtoEvents.auditFinding("", bare)).toFinding())
                .isEqualTo(bare);
    }

    @Test
    void format_request_round_trips_the_resolved_styles() {
        String json = new FormatRequest(
                        "/work", "/cache", true, "palantir", "kotlinlang", false, true, false, true, false)
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.FORMAT_REQUEST);
        assertThat(Jsonl.bool(json, "check", false)).isTrue();
        assertThat(Jsonl.str(json, "javaStyle")).isEqualTo("palantir");
        assertThat(Jsonl.str(json, "kotlinStyle")).isEqualTo("kotlinlang");
        assertThat(Jsonl.bool(json, "optimizeImports", true)).isFalse();
        assertThat(Jsonl.bool(json, "importOrder", false)).isTrue();
        assertThat(Jsonl.bool(json, "removeUnusedImports", true)).isFalse();
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
    }

    @Test
    void format_file_event_and_finish_variant_round_trip() {
        String file = ProtoEvents.formatFile("", "/src/A.java", "changed", null, 3, 12);
        assertThat(EngineProtocol.typeOf(file)).isEqualTo(EngineProtocol.FORMAT_FILE);
        assertThat(Jsonl.str(file, "path")).isEqualTo("/src/A.java");
        assertThat(Jsonl.str(file, "status")).isEqualTo("changed");
        assertThat(Jsonl.intValue(file, "index", -1)).isEqualTo(3);
        assertThat(Jsonl.intValue(file, "total", -1)).isEqualTo(12);

        String finish = ProtoEvents.planFinishFormat("", true, 12, 1);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.intValue(finish, "formatTotal", -1)).isEqualTo(12);
        assertThat(Jsonl.intValue(finish, "formatWorkerExit", -1)).isEqualTo(1);
        // Only the two live fields ride: the CLI tallies changed/clean/errors from the
        // per-file format-file stream, so a wire tally here would be a second, poorer copy.
        assertThat(finish)
                .doesNotContain("formatChanged")
                .doesNotContain("formatClean")
                .doesNotContain("formatErrors");
    }

    @Test
    void publish_request_round_trips_the_credential_fields() {
        String json = new PublishRequest(
                        "/work",
                        "/cache",
                        "https://repo/m2",
                        "us-east-1",
                        null,
                        "/out/app.jar",
                        true,
                        false,
                        "/key.asc",
                        "s3cret",
                        false,
                        true,
                        true,
                        "basic",
                        "alice",
                        "hunter2",
                        null,
                        true,
                        false,
                        false,
                        null)
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PUBLISH_REQUEST);
        // A publish uploads to someone else's server: the offline decision rides the request so
        // the worker can refuse rather than PUT behind an offline run's back.
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("https://repo/m2");
        assertThat(Jsonl.str(json, "region")).isEqualTo("us-east-1");
        assertThat(Jsonl.str(json, "endpoint")).isNull();
        assertThat(Jsonl.str(json, "jar")).isEqualTo("/out/app.jar");
        assertThat(Jsonl.bool(json, "allowSnapshot", false)).isTrue();
        assertThat(Jsonl.str(json, "keyFile")).isEqualTo("/key.asc");
        assertThat(Jsonl.str(json, "gpgPassphrase")).isEqualTo("s3cret");
        assertThat(Jsonl.bool(json, "slsa", false)).isTrue();
        assertThat(Jsonl.bool(json, "sbom", false)).isTrue();
        assertThat(Jsonl.str(json, "authType")).isEqualTo("basic");
        assertThat(Jsonl.str(json, "user")).isEqualTo("alice");
        assertThat(Jsonl.str(json, "pass")).isEqualTo("hunter2");
        assertThat(Jsonl.str(json, "token")).isNull();

        String finish = ProtoEvents.planFinishPublish("", true, 9, List.of("/w/target/sbom/a-1.cdx.json"));
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.intValue(finish, "publishFiles", -1)).isEqualTo(9);
        assertThat(PlanFinishPublishEvent.decode(finish).written()).containsExactly("/w/target/sbom/a-1.cdx.json");
        assertThat(PlanFinishPublishEvent.decode(ProtoEvents.planFinishPublish("", true, 9))
                        .written())
                .isEmpty();
    }

    @Test
    void image_request_keeps_the_tarball_tristate() {
        String none = new ImageRequest(
                        "/w", "/c", null, "com.example.Main", null, null, null, null, false, false, false, false)
                .encode();
        assertThat(EngineProtocol.typeOf(none)).isEqualTo(EngineProtocol.IMAGE_REQUEST);
        assertThat(Jsonl.str(none, "tarball")).isNull();
        String defaulted =
                new ImageRequest("/w", "/c", null, null, null, null, "", null, false, false, false, false).encode();
        assertThat(Jsonl.str(defaulted, "tarball")).isEmpty();
        String explicit = new ImageRequest(
                        "/w", "/c", null, null, "reg.io", "v2", "/out/img.tar", "podman", true, true, true, true)
                .encode();
        assertThat(Jsonl.str(explicit, "tarball")).isEqualTo("/out/img.tar");
        assertThat(Jsonl.str(explicit, "registry")).isEqualTo("reg.io");
        assertThat(Jsonl.str(explicit, "dockerExecutable")).isEqualTo("podman");
        assertThat(Jsonl.bool(explicit, "skipTests", false)).isTrue();
        // rebuild rides the session envelope (withSession), not the image builder.
    }

    @Test
    void goal_finish_image_variant_carries_the_success_tail_fields_and_test_counts() {
        String json = ProtoEvents.planFinishImage("", true, 12, 12, 0, 0, "reg.io/app:1.0", null, "app", "1.0", null);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        // One wire spelling: the counts ride as the nested `tests` object, never flat scalars.
        TestSummary counts = requireNonNull(TestSummary.readCounts(json));
        assertThat(counts).isNotNull();
        assertThat(counts.total()).isEqualTo(12);
        assertThat(Jsonl.str(json, "imageRef")).isEqualTo("reg.io/app:1.0");
        assertThat(Jsonl.str(json, "imageTarball")).isNull();
        assertThat(Jsonl.str(json, "imageName")).isEqualTo("app");
        assertThat(Jsonl.str(json, "imageVersion")).isEqualTo("1.0");
        assertThat(Jsonl.str(json, "imageDaemonExe")).isNull();
    }

    @Test
    void import_request_note_and_finish_variant_round_trip() {
        String req = new ImportRequest("/p/pom.xml", "/p/jk.toml", "/p", "/tmp/jk", true, null, "/cache").encode();
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Jsonl.str(req, "source")).isEqualTo("/p/pom.xml");
        assertThat(Jsonl.str(req, "out")).isEqualTo("/p/jk.toml");
        assertThat(Jsonl.bool(req, "force", false)).isTrue();
        assertThat(Jsonl.str(req, "report")).isNull();

        String note = ProtoEvents.importNote("", "wrote", "/p/jk.toml");
        assertThat(EngineProtocol.typeOf(note)).isEqualTo(EngineProtocol.IMPORT_NOTE);
        assertThat(Jsonl.str(note, "kind")).isEqualTo("wrote");
        assertThat(Jsonl.str(note, "text")).isEqualTo("/p/jk.toml");

        String finish = ProtoEvents.planFinishImport("", true, 0, 3, null, null);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.intValue(finish, "importExit", -1)).isEqualTo(0);
        assertThat(Jsonl.intValue(finish, "importWarnings", -1)).isEqualTo(3);
        assertThat(Jsonl.str(finish, "importError")).isNull();
    }

    @Test
    void provision_request_and_result_round_trip() {
        String req = new ProvisionRequest("/proj", "/cache/tools", true, true, true, null, null).encode();
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.PROVISION_REQUEST);
        // Project directory is always "dir" (never projectDir) — freeze invariant.
        assertThat(Jsonl.str(req, "dir")).isEqualTo("/proj");
        assertThat(req).doesNotContain("projectDir");
        assertThat(Jsonl.str(req, "toolsRoot")).isEqualTo("/cache/tools");
        assertThat(Jsonl.bool(req, "noDiscover", false)).isTrue();
        assertThat(Jsonl.bool(req, "acceptUnverified", false)).isTrue();
        assertThat(Jsonl.bool(req, "gradle", false)).isTrue();

        String result = ProtoEvents.provisionResult(
                "/cache/tools/mvn/bin/mvn", "3.9.9", "DOWNLOADED", "verified against the published .sha512", null, 0);
        assertThat(EngineProtocol.typeOf(result)).isEqualTo(EngineProtocol.PROVISION_RESULT);
        assertThat(Jsonl.str(result, "bin")).isEqualTo("/cache/tools/mvn/bin/mvn");
        assertThat(Jsonl.str(result, "version")).isEqualTo("3.9.9");
        assertThat(Jsonl.str(result, "source")).isEqualTo("DOWNLOADED");
        assertThat(Jsonl.str(result, "verification")).isEqualTo("verified against the published .sha512");
        assertThat(Jsonl.str(result, "error")).isNull();
        assertThat(Jsonl.intValue(result, "exit", -1)).isEqualTo(0);
    }

    @Test
    void mvn_results_request_and_result_round_trip() {
        String req = new MvnResultsRequest("/proj", "/tmp/jk/events.tsv", 1, 4_200L, "clean test").encode();
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.MVN_RESULTS_REQUEST);
        assertThat(Jsonl.str(req, "dir")).isEqualTo("/proj");
        MvnResultsRequest back = MvnResultsRequest.decode(req);
        assertThat(back).isEqualTo(new MvnResultsRequest("/proj", "/tmp/jk/events.tsv", 1, 4_200L, "clean test"));

        String result = ProtoEvents.mvnResultsResult("/proj/target/jk-results.md", null);
        assertThat(EngineProtocol.typeOf(result)).isEqualTo(EngineProtocol.MVN_RESULTS_RESULT);
        assertThat(MvnResultsResultEvent.decode(result))
                .isEqualTo(new MvnResultsResultEvent("/proj/target/jk-results.md", null));
    }

    @Test
    void auth_envelope_is_typed_token_not_a_raw_line() {
        String line = ProtoLifecycle.auth("secret-token");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.AUTH);
        assertThat(Jsonl.str(line, "token")).isEqualTo("secret-token");
        assertThat(line).startsWith("{\"type\":");
    }

    @Test
    void hello_purpose_is_connect_or_probe() {
        assertThat(Jsonl.str(ProtoLifecycle.hello("1.0.0"), "purpose")).isEqualTo("connect");
        assertThat(Jsonl.str(ProtoLifecycle.hello("1.0.0", "probe"), "purpose")).isEqualTo("probe");
        assertThat(Jsonl.intValue(ProtoLifecycle.hello("1.0.0"), "proto", -1)).isEqualTo(EngineProtocol.PROTOCOL);
    }

    @Test
    void hosted_request_project_directory_field_is_always_dir() {
        // Sample of hosted builders — none may invent a second spelling for the project path.
        assertThat(Jsonl.str(new CompileRequest("/w", "/c", null, false, false, false, List.of()).encode(), "dir"))
                .isEqualTo("/w");
        assertThat(Jsonl.str(buildRequest(false, false, null), "dir")).isEqualTo("/w");
        assertThat(Jsonl.str(new ProvisionRequest("/w", "/t", false, false, false, null, null).encode(), "dir"))
                .isEqualTo("/w");
        assertThat(Jsonl.str(ProtoEvents.planFinish("/w", true), "dir")).isEqualTo("/w");
    }

    @Test
    void compile_request_round_trips_all_fields() {
        String json = new CompileRequest("/work", "/cache", "ci", true, false, true, List.of()).encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.COMPILE_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.str(json, "profile")).isEqualTo("ci");
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", true)).isFalse();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();

        String noProfile = new CompileRequest("/w", "/c", null, false, false, false, List.of()).encode();
        assertThat(Jsonl.str(noProfile, "profile")).isNull();
    }

    @Test
    void native_request_round_trips_the_graal_home_map() {
        var graal = new LinkedHashMap<String, String>();
        graal.put("/work/app", "/graal/a");
        graal.put("/work/tool", "/graal/b");
        String json = new NativeRequest(
                        "/work",
                        "/cache",
                        "/jdks",
                        "com.example.Main",
                        true,
                        false,
                        true,
                        false,
                        List.of("-O2"),
                        graal,
                        List.of())
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.NATIVE_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.str(json, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
        assertThat(Jsonl.strArray(json, "extraArgs")).containsExactly("-O2");
        assertThat(Jsonl.strMap(json, "graalHomes"))
                .containsExactly(Map.entry("/work/app", "/graal/a"), Map.entry("/work/tool", "/graal/b"));
    }

    @Test
    void install_request_round_trips_all_fields() {
        String json = new InstallRequest("/work", "/cache", "/jdks", "/home/u/.m2", "/graal", true, false, false, true)
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.INSTALL_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, ProtoJobs.JDKS_DIR)).isEqualTo("/jdks");
        assertThat(InstallRequest.decode(json).jdksDir()).isEqualTo("/jdks");
        assertThat(Jsonl.str(json, "m2Dir")).isEqualTo("/home/u/.m2");
        assertThat(Jsonl.str(json, "graalHome")).isEqualTo("/graal");
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();

        String jvmOnly = new InstallRequest("/w", "/c", null, "/m2", null, false, false, false, false).encode();
        assertThat(Jsonl.str(jvmOnly, "graalHome")).isNull();
        assertThat(Jsonl.str(jvmOnly, ProtoJobs.JDKS_DIR)).isNull();
    }

    @Test
    void git_fetch_request_and_finish_variant_round_trip() {
        String req = new GitFetchRequest("https://github.com/o/r.git", "github.com/o/r", "v1.2", "/cache", true, false)
                .encode();
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.GIT_FETCH_REQUEST);
        assertThat(Jsonl.str(req, "url")).isEqualTo("https://github.com/o/r.git");
        assertThat(Jsonl.str(req, "canonicalUrl")).isEqualTo("github.com/o/r");
        assertThat(Jsonl.str(req, "ref")).isEqualTo("v1.2");
        assertThat(Jsonl.bool(req, "refresh", false)).isTrue();
        assertThat(Jsonl.bool(req, "requireJkToml", true)).isFalse();

        String finish = ProtoEvents.planFinishGitFetch("", true, "/cache/git/co/abc", "abc123");
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.str(finish, "gitCheckout")).isEqualTo("/cache/git/co/abc");
        assertThat(Jsonl.str(finish, "gitSha")).isEqualTo("abc123");

        String failed = ProtoEvents.planFinishGitFetch("", false, null, null);
        assertThat(Jsonl.str(failed, "gitCheckout")).isNull();
        assertThat(Jsonl.str(failed, "gitSha")).isNull();
    }

    @Test
    void explain_request_carries_the_eta_inputs() {
        String json = new ExplainRequest(
                        "/work",
                        "/cache",
                        4,
                        true,
                        "ci",
                        "/jdks",
                        true,
                        true,
                        false,
                        false,
                        1,
                        List.of("api", "affected:main"),
                        TestSelection.DEFAULT)
                .encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.EXPLAIN_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.intValue(json, "workers", -1)).isEqualTo(4);
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.str(json, "profile")).isEqualTo("ci");
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.bool(json, "serial", false)).isTrue();
        assertThat(Jsonl.bool(json, "parallelTests", false)).isTrue();
        assertThat(Jsonl.strArray(json, "modules")).containsExactly("api", "affected:main");
        assertThat(ExplainRequest.decode(json).modules()).containsExactly("api", "affected:main");

        String defaults = new ExplainRequest(
                        "/w",
                        "/c",
                        1,
                        false,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        0,
                        List.of(),
                        TestSelection.DEFAULT)
                .encode();
        assertThat(Jsonl.str(defaults, "profile")).isNull();
        assertThat(Jsonl.str(defaults, "jdksDir")).isNull();
        assertThat(ExplainRequest.decode(defaults).modules()).isEmpty();
    }

    @Test
    void tool_resolve_request_and_finish_variant_round_trip() {
        String req = new ToolResolveRequest(
                        "com.example:widget-cli:1.0.0",
                        List.of("com.example:extra@1.2"),
                        "widget",
                        "com.example.Main",
                        "http://repo",
                        "/cache")
                .encode();
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.TOOL_RESOLVE_REQUEST);
        assertThat(Jsonl.str(req, "coord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Jsonl.strArray(req, "with")).containsExactly("com.example:extra@1.2");
        assertThat(Jsonl.str(req, "bin")).isEqualTo("widget");
        assertThat(Jsonl.str(req, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.str(req, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.str(req, "cache")).isEqualTo("/cache");

        String defaults = new ToolResolveRequest("g:a:1", List.of(), "a", null, null, "/c").encode();
        assertThat(Jsonl.str(defaults, "mainClass")).isNull();
        assertThat(Jsonl.str(defaults, "repoUrl")).isNull();
        assertThat(Jsonl.strArray(defaults, "with")).isEmpty();

        String finish = ProtoSession.planFinishTool(
                "",
                true,
                "com.example:widget-cli:1.0.0",
                "com.example.Main",
                List.of("/cas/aa/1.jar", "/cas/bb/2.jar"));
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.str(finish, "toolCoord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Jsonl.str(finish, "toolMainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.strArray(finish, "toolClasspath")).containsExactly("/cas/aa/1.jar", "/cas/bb/2.jar");

        String failed = ProtoSession.planFinishTool("", false, null, null, List.of());
        assertThat(Jsonl.str(failed, "toolCoord")).isNull();
        assertThat(Jsonl.str(failed, "toolMainClass")).isNull();
        assertThat(Jsonl.strArray(failed, "toolClasspath")).isEmpty();
    }

    @Test
    void cache_prune_request_round_trips_all_fields() {
        String json = new CachePruneRequest("prune", "/cache", null, true, true).encode();
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.CACHE_PRUNE_REQUEST);
        assertThat(Jsonl.str(json, "op")).isEqualTo("prune");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.bool(json, "dryRun", false)).isTrue();
        assertThat(Jsonl.bool(json, "includeJkTmp", false)).isTrue();

        String purge = new CachePruneRequest("purge", "/c", null, false, false).encode();
        assertThat(Jsonl.str(purge, "op")).isEqualTo("purge");
    }

    @Test
    void prune_wait_round_trips_plans_and_external() {
        String inEngine = ProtoSession.pruneWait(3, false);
        assertThat(EngineProtocol.typeOf(inEngine)).isEqualTo(EngineProtocol.PRUNE_WAIT);
        assertThat(Jsonl.intValue(inEngine, "plans", -1)).isEqualTo(3);
        assertThat(Jsonl.bool(inEngine, "external", true)).isFalse();

        String external = ProtoSession.pruneWait(0, true);
        assertThat(Jsonl.bool(external, "external", false)).isTrue();
    }

    @Test
    void cache_finish_variant_round_trips_the_summary() {
        String json = ProtoSession.planFinishCache("", true, 12, 34_567);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.bool(json, "success", false)).isTrue();
        assertThat(Jsonl.longValue(json, "cacheFiles", -99)).isEqualTo(12);
        assertThat(Jsonl.longValue(json, "cacheBytes", -99)).isEqualTo(34_567);
    }

    private static String buildRequest(boolean freshenLock, boolean testOnly, @Nullable List<String> dirtyHint) {
        return new BuildRequest(
                        "/w",
                        "/c",
                        null,
                        1,
                        null,
                        false,
                        false,
                        0,
                        false,
                        false,
                        false,
                        freshenLock,
                        false,
                        testOnly,
                        dirtyHint,
                        TestSelection.DEFAULT,
                        null,
                        List.of(),
                        false,
                        null,
                        Map.of(),
                        null,
                        null,
                        null)
                .encode();
    }
}
