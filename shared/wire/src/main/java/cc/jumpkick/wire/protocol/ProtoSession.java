// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The session envelope — variant, client env, worker-JVM tuning, toolchain selection, trigger —
 * spliced onto any encoded request line, with its decoders and {@link #sessionOf the one
 * request-to-Session constructor}; plus the hosted long-tail {@code plan-finish} variants (tool,
 * script, cache) and the {@code prune-wait} notice.
 */
public final class ProtoSession {

    private ProtoSession() {}

    /**
     * The {@link Session} a request runs under: every plan-affecting field the line carries — the
     * flat config flags, the paths, the test knobs and selection, and the {@link #withSession
     * session} and {@link #withToolchain toolchain} envelopes. Every verb that plans, builds or
     * forecasts takes its session from here and nowhere else, so a forecast and the build it
     * forecasts run under the same session by construction. Absent fields take their wire defaults;
     * an absent {@code cache} keeps the engine's own.
     */
    public static Session sessionOf(String request, Session.CancelToken cancel) {
        JkConfig config = JkConfig.empty()
                .withOffline(Jsonl.bool(request, "offline", false))
                .withRebuild(Jsonl.bool(request, "rebuild", false))
                .withVerbose(Jsonl.bool(request, "verbose", false))
                .withForce(Jsonl.bool(request, "force", false));
        Session session = Session.defaults()
                .withConfig(config)
                .withWorkingDir(Path.of(Objects.requireNonNull(Jsonl.str(request, "dir"), "dir")))
                .withJdksDir(pathOf(Jsonl.str(request, ProtoJobs.JDKS_DIR)))
                .withCancel(cancel)
                .withJvm(jvmTuning(request))
                .withParallelTests(Jsonl.bool(request, "parallelTests", true))
                .withRequestedTestWorkers(Jsonl.intValue(request, "workers", 0))
                .withTestSelection(ProtoJobs.testSelectionOf(request))
                .withAffected(Jsonl.bool(request, "affected", false))
                .withVariant(variantOf(request), clientEnvOf(request))
                .withToolchainSpecs(jdkSpecOf(request), graalSpecOf(request), graalHomeOf(request))
                .withAssemblyOverride(assemblyOverrideOf(request));
        Path cache = pathOf(Jsonl.str(request, "cache"));
        return cache == null ? session : session.withCacheDir(cache);
    }

    private static @Nullable Path pathOf(@Nullable String s) {
        return s == null || s.isBlank() ? null : Path.of(s);
    }

    /**
     * As {@link ProtoEvents#planFinish(String, boolean)}, additionally carrying a {@link EngineProtocol#TOOL_RESOLVE_REQUEST}
     * result: the pinned {@code g:a:v} the resolve landed on (a floating spec's concrete version is
     * decided engine-side against maven-metadata), the resolved {@code Main-Class}, and the
     * transitive classpath in resolution order (absolute CAS paths — a flat string array, per the
     * codec's no-nested-objects rule). All {@code null}/empty when the resolve failed.
     */
    public static String planFinishTool(
            String dir, boolean success, @Nullable String coord, @Nullable String mainClass, List<String> classpath) {
        return new PlanFinishToolEvent(dir, success, coord, mainClass, classpath).encode();
    }

    /**
     * As {@link ProtoEvents#planFinish(String, boolean)}, additionally carrying a {@link
     * EngineProtocol#SCRIPT_PREPARE_REQUEST} result: the exec ingredients the client-side launch needs. Fields
     * not applicable to the prepared mode (and everything on failure) are {@code null}/empty.
     */
    public static String planFinishScript(
            String dir,
            boolean success,
            @Nullable String mainClass,
            List<String> classpath,
            @Nullable String classesDir,
            @Nullable String kotlincBin,
            @Nullable String stdlib) {
        return new PlanFinishScriptEvent(dir, success, mainClass, classpath, classesDir, kotlincBin, stdlib).encode();
    }

    /** The maintenance job is waiting for the cache to quiesce (see {@link EngineProtocol#PRUNE_WAIT}). */
    public static String pruneWait(int plans, boolean external) {
        return new PruneWaitEvent(plans, external).encode();
    }

    /**
     * As {@link ProtoEvents#planFinish(String, boolean)}, additionally carrying a {@link EngineProtocol#CACHE_PRUNE_REQUEST}
     * summary: files removed + bytes freed (what would be removed, on a dry run). {@code -1} = the op
     * reported no count.
     */
    public static String planFinishCache(String dir, boolean success, long files, long bytes) {
        return new PlanFinishCacheEvent(dir, success, files, bytes).encode();
    }

    /**
     * Append the client's flag/env JVM-tuning layer to an already-encoded request line (thin-client
     * contract: the {@code jk.toml [jvm]} table never resolves client-side — the engine overlays it
     * at worker-fork time; only {@code --ram-percent}/{@code --jvm-arg} and {@code JK_JVM_*}
     * cross the wire). A NONE tuning returns the line unchanged, so absent fields stay absent.
     */
    /**
     * Attach the session envelope — variant selection, client-resolved env values, and worker-JVM
     * tuning — to an encoded request line. The ONE attachment point for session state: every
     * hosted request rides it (so {@code --jvm-arg}/{@code JK_JVM_*} apply to every command that
     * forks workers, not an arbitrary subset), and an empty envelope leaves the line byte-
     * identical. The splice is validated: {@code request} must be a one-line encoded object.
     */
    public static String withSession(
            String request,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv,
            @Nullable PluginTuning t) {
        return withSession(request, variant, clientEnv, t, false, false);
    }

    /** As above, additionally carrying the session's {@code rebuild} distrust flag when set. */
    public static String withSession(
            String request,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv,
            @Nullable PluginTuning t,
            boolean rebuild) {
        return withSession(request, variant, clientEnv, t, rebuild, false);
    }

    /**
     * As above, with {@code noTimeline} (skip chrome profile write). {@code rebuild} distrusts action
     * cache; {@code noTimeline} is independent.
     */
    public static String withSession(
            String request,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv,
            @Nullable PluginTuning t,
            boolean rebuild,
            boolean noTimeline) {
        return withSession(request, variant, clientEnv, t, rebuild, noTimeline, null);
    }

    /**
     * Session envelope including optional {@code assemblyOverride} ({@code fat} / {@code minified}) for
     * {@code jk assemble --minified} one-offs.
     */
    public static String withSession(
            String request,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv,
            @Nullable PluginTuning t,
            boolean rebuild,
            boolean noTimeline,
            @Nullable String assemblyOverride) {
        boolean hasJvm = t != null
                && (t.maxRamPercent() != null
                        || t.gc() != null
                        || t.stringDedup() != null
                        || !t.extraArgs().isEmpty());
        RequestJson b = RequestJson.fields()
                .optionalTrue("rebuild", rebuild)
                .optionalTrue("noTimeline", noTimeline)
                .optionalNonBlankString("variant", variant)
                .optionalMap("env", clientEnv)
                .optionalNonBlankString("assemblyOverride", assemblyOverride);
        if (hasJvm && t != null) {
            if (t.maxRamPercent() != null) b.string("jvmMaxRam", String.valueOf(t.maxRamPercent()));
            b.optionalString("jvmGc", t.gc());
            if (t.stringDedup() != null) b.string("jvmStringDedup", String.valueOf(t.stringDedup()));
            b.optionalArray("jvmArgs", t.extraArgs());
        }
        // The splicer owns the comma that joins the fragment to the request — it depends on whether
        // the request is `{}`.
        return Jsonl.append(request, b.body());
    }

    /**
     * Attach the request's <strong>toolchain selection</strong> to an encoded request line.
     *
     * <p>{@code jdk} and {@code graal} are the top-tier selections — {@code --jdk} / {@code --graal},
     * with the {@code JK_JDK} / {@code JK_GRAAL} environment spellings already folded in by the
     * client. They have to ride the request: the engine is a daemon, so a selection that stayed on
     * the client's {@code Session} was invisible to it, and every engine-side resolver fell through
     * to whichever JDK the shell that started the daemon happened to name.
     *
     * <p>Folding the switch and the env spelling into one field loses no fidelity.
     * {@code JdkResolution} walks {@code SWITCH} then {@code JK_ENV} with no tier between them, so
     * "switch, else env" picks exactly what the two-tier walk picks — and the client is the one place
     * where {@code System.getenv} genuinely means the caller.
     *
     * <p>A separate splice rather than two more {@code withSession} parameters: the envelope has four
     * arities and only some callers reach the widest, so threading it there would have added two
     * arguments to every one of them. Nothing selected → the line rides unchanged.
     */
    public static String withToolchain(
            String request, @Nullable String jdk, @Nullable String graal, @Nullable String graalHome) {
        String body = RequestJson.fields()
                .optionalNonBlankString("jdk", jdk)
                .optionalNonBlankString("graal", graal)
                .optionalNonBlankString("graalHome", graalHome)
                .body();
        if (body.isEmpty()) return request;
        return Jsonl.append(request, body);
    }

    /**
     * Attach the journal-classification {@code trigger} ({@code web}, {@code optimize}, …) to an
     * encoded request line. The engine synthesizes wire lines for HTTP/MCP job submissions and
     * marks them here — same validated splice as {@link #withSession}, never call-site string
     * surgery. A null/blank trigger returns the line unchanged.
     */
    public static String withTrigger(String request, @Nullable String trigger) {
        if (trigger == null || trigger.isBlank()) return request;
        return Jsonl.append(
                request, RequestJson.fields().string("trigger", trigger).body());
    }

    /** Decode {@code assemblyOverride} from a session envelope ({@code fat}/{@code minified}/empty). */
    public static String assemblyOverrideOf(String request) {
        String v = Jsonl.str(request, "assemblyOverride");
        return v == null ? "" : v;
    }

    /**
     * Append a variant selection + client-resolved env values to an encoded build request (thin
     * client: the engine folds the selection into plugin configs at parse time; env values are the
     * user's shell environment, resolved client-side for env:-indirected plugin config — signing
     * credentials — because the engine's own environment belongs to whichever invocation spawned
     * it). Nothing selected and no env → the line rides unchanged.
     */

    /**
     * Decode side of {@link #withToolchain}: the request's JDK selection, or {@code null}.
     *
     * <p>Engine verbs feed this to {@code Session.withToolchainSpecs} so {@code JdkResolution}'s
     * {@code SWITCH} tier sees the caller's choice instead of an empty one.
     */
    public static @Nullable String jdkSpecOf(String request) {
        String v = Jsonl.str(request, "jdk");
        return v == null || v.isBlank() ? null : v;
    }

    /** Decode side of {@link #withToolchain}: the request's GraalVM selection, or {@code null}. */
    public static @Nullable String graalSpecOf(String request) {
        String v = Jsonl.str(request, "graal");
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Decode side of {@link #withToolchain}: the caller's {@code GRAALVM_HOME}, or {@code null}.
     *
     * <p>A home path rather than a spec, so it cannot ride the {@code graal} field. It has to come
     * from the request for the same reason the specs do: a {@code System.getenv} inside a resident
     * engine answers from the shell that started the daemon.
     */
    public static @Nullable Path graalHomeOf(String request) {
        String v = Jsonl.str(request, "graalHome");
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    /** Decode side of {@link #withSession}: the selection, or {@code ""}. */
    public static String variantOf(String request) {
        String v = Jsonl.str(request, "variant");
        return v == null ? "" : v;
    }

    /** Decode side of {@link #withSession}: the client-resolved env values, or empty. */
    public static Map<String, String> clientEnvOf(String request) {
        return Jsonl.strMap(request, "env");
    }

    /** Decode side of {@link #withSession}; NONE when the request carries no tuning fields. */
    public static PluginTuning jvmTuning(String request) {
        String maxRam = Jsonl.str(request, "jvmMaxRam");
        String gc = Jsonl.str(request, "jvmGc");
        String dedup = Jsonl.str(request, "jvmStringDedup");
        List<String> args = Jsonl.strArray(request, "jvmArgs");
        if (maxRam == null && gc == null && dedup == null && args.isEmpty()) {
            return PluginTuning.NONE;
        }
        Double ram = null;
        try {
            if (maxRam != null) ram = Double.valueOf(maxRam);
        } catch (NumberFormatException ignored) {
            // a malformed number degrades to absent, like every tolerant config read
        }
        return new PluginTuning(ram, gc, dedup == null ? null : Boolean.valueOf(dedup), args);
    }
}
