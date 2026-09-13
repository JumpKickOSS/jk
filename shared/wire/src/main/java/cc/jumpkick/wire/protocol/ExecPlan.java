// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.DevReady;
import cc.jumpkick.model.Sidecar.Restart;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Engine-computed execution plan ({@link EngineProtocol#EXEC_PLAN_REQUEST}): run/dev argv, install
 * layout, or aot-cache layout. Non-null {@code error} is printable; non-empty {@code mainIssue} is
 * {@code missing}/{@code ambiguous} so the client can restyle main-class scan failures.
 */
public record ExecPlan(
        @Nullable String error,
        String mainIssue,
        String kind,
        List<String> argv,
        String workingDir,
        String display,
        String javaHome,
        boolean hotReload,
        boolean devtoolsInjected,
        List<String> watchRoots,
        List<String> linkSrcs,
        List<String> linkDests,
        String launcherPath,
        String launcherScript,
        String binPath,
        boolean boot,
        String mainJar,
        String tier,
        String mainClass,
        List<String> libNames,
        List<String> libPaths,
        String deployCommand,
        /** {@code [dev.sidecars]} resolved for this module — dev plans only; every other kind carries none. */
        List<Sidecar> sidecars,
        /** {@code [dev] ready} — the app's own probe; {@link Probe#NONE} on every plan but a dev plan that declares one. */
        Probe appReady) {

    /**
     * A readiness probe as the manifest states it — the application's under {@code [dev]}, a
     * sidecar's under its entry: {@code ready} a URL polled for 2xx/3xx, {@code readyPattern} a
     * regex over the process's output, one or neither, and the timeout bounding whichever is set.
     * Written flat beside its owner's other scalars.
     */
    public record Probe(String ready, String readyPattern, long readyTimeoutMillis) {

        /** No probe: the app is ready once forked, a sidecar once it has stayed alive a moment. */
        public static final Probe NONE = new Probe("", "", 0);

        /** {@code ready} on the wire, or {@link #NONE} when the process declares no probe. */
        public static Probe of(@Nullable DevReady ready) {
            if (ready == null) return NONE;
            return new Probe(
                    ready.url() == null ? "" : ready.url(),
                    ready.pattern() == null ? "" : ready.pattern(),
                    ready.timeoutMillis());
        }

        public boolean isEmpty() {
            return ready.isEmpty() && readyPattern.isEmpty();
        }

        static Probe decode(String line) {
            String ready = Jsonl.str(line, "appReady");
            String pattern = Jsonl.str(line, "appReadyPattern");
            return new Probe(
                    ready == null ? "" : ready,
                    pattern == null ? "" : pattern,
                    Jsonl.longValue(line, "appReadyTimeoutMillis", 0));
        }
    }

    /**
     * One sidecar the client is to run beside the app: {@code cwd} absolute, {@code env} the
     * values to lay over the inherited environment, {@code probe} as the manifest states it
     * ({@link Probe#NONE} when unset), written flat. Every field is written and every field is
     * required on decode.
     */
    public record Sidecar(
            String name,
            List<String> command,
            String cwd,
            Map<String, String> env,
            Probe probe,
            boolean frontDoor,
            Restart restart) {

        public Sidecar {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }

        String encode() {
            return JsonFields.object()
                    .string("name", name)
                    .array("command", command)
                    .string("cwd", cwd)
                    .map("env", env)
                    .string("ready", probe.ready())
                    .string("readyPattern", probe.readyPattern())
                    .number("readyTimeoutMillis", probe.readyTimeoutMillis())
                    .bool("frontDoor", frontDoor)
                    .string("restart", restart.manifestValue())
                    .finish();
        }

        static Sidecar decode(String object) {
            for (String key : List.of("command", "env", "frontDoor")) {
                if (!Jsonl.has(object, key)) throw malformed(key);
            }
            long timeout = Jsonl.longValue(object, "readyTimeoutMillis", Long.MIN_VALUE);
            if (timeout == Long.MIN_VALUE) throw malformed("readyTimeoutMillis");
            return new Sidecar(
                    required(object, "name"),
                    Jsonl.strArray(object, "command"),
                    required(object, "cwd"),
                    Jsonl.strMap(object, "env"),
                    new Probe(required(object, "ready"), required(object, "readyPattern"), timeout),
                    Jsonl.bool(object, "frontDoor", false),
                    Restart.parse(required(object, "restart")));
        }

        private static String required(String object, String key) {
            String value = Jsonl.str(object, key);
            if (value == null) throw malformed(key);
            return value;
        }

        private static IllegalArgumentException malformed(String key) {
            return new IllegalArgumentException("malformed exec plan: sidecar lacks `" + key + "`");
        }

        static String encodeAll(List<Sidecar> sidecars) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < sidecars.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(sidecars.get(i).encode());
            }
            return sb.append(']').toString();
        }

        static List<Sidecar> decodeAll(String line) {
            List<Sidecar> out = new ArrayList<>();
            for (String object : Jsonl.objectArray(line, "sidecars")) out.add(decode(object));
            return List.copyOf(out);
        }
    }

    public static ExecPlan error(@Nullable String kind, String message) {
        return error(kind, message, "");
    }

    /** This plan launching {@code argv} instead, described by {@code display}; everything else as is. */
    public ExecPlan withArgv(List<String> argv, String display) {
        return new ExecPlan(
                error,
                mainIssue,
                kind,
                List.copyOf(argv),
                workingDir,
                display,
                javaHome,
                hotReload,
                devtoolsInjected,
                watchRoots,
                linkSrcs,
                linkDests,
                launcherPath,
                launcherScript,
                binPath,
                boot,
                mainJar,
                tier,
                mainClass,
                libNames,
                libPaths,
                deployCommand,
                sidecars,
                appReady);
    }

    /** As {@link #error(String, String)}, tagging the failure as an unresolved main-class scan. */
    public static ExecPlan error(@Nullable String kind, String message, String mainIssue) {
        return new ExecPlan(
                message,
                mainIssue,
                kind == null ? "" : kind,
                List.of(),
                "",
                "",
                "",
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                false,
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                List.of(),
                Probe.NONE);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.EXEC_PLAN_ACK)
                .string("error", error)
                .string("mainIssue", mainIssue)
                .string("kind", kind)
                .array("argv", argv)
                .string("workingDir", workingDir)
                .string("display", display)
                .string("javaHome", javaHome)
                .bool("hotReload", hotReload)
                .bool("devtoolsInjected", devtoolsInjected)
                .array("watchRoots", watchRoots)
                .array("linkSrcs", linkSrcs)
                .array("linkDests", linkDests)
                .string("launcherPath", launcherPath)
                .string("launcherScript", launcherScript)
                .string("binPath", binPath)
                .bool("boot", boot)
                .string("mainJar", mainJar)
                .string("tier", tier)
                .string("mainClass", mainClass)
                .array("libNames", libNames)
                .array("libPaths", libPaths)
                .string("deployCommand", deployCommand)
                .token("sidecars", Sidecar.encodeAll(sidecars))
                .string("appReady", appReady.ready())
                .string("appReadyPattern", appReady.readyPattern())
                .number("appReadyTimeoutMillis", appReady.readyTimeoutMillis())
                .finish();
    }

    public static ExecPlan decode(String line) {
        return new ExecPlan(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "mainIssue")),
                orEmpty(Jsonl.str(line, "kind")),
                Jsonl.strArray(line, "argv"),
                orEmpty(Jsonl.str(line, "workingDir")),
                orEmpty(Jsonl.str(line, "display")),
                orEmpty(Jsonl.str(line, "javaHome")),
                Jsonl.bool(line, "hotReload", false),
                Jsonl.bool(line, "devtoolsInjected", false),
                Jsonl.strArray(line, "watchRoots"),
                Jsonl.strArray(line, "linkSrcs"),
                Jsonl.strArray(line, "linkDests"),
                orEmpty(Jsonl.str(line, "launcherPath")),
                orEmpty(Jsonl.str(line, "launcherScript")),
                orEmpty(Jsonl.str(line, "binPath")),
                Jsonl.bool(line, "boot", false),
                orEmpty(Jsonl.str(line, "mainJar")),
                orEmpty(Jsonl.str(line, "tier")),
                orEmpty(Jsonl.str(line, "mainClass")),
                Jsonl.strArray(line, "libNames"),
                Jsonl.strArray(line, "libPaths"),
                orEmptyDeploy(Jsonl.str(line, "deployCommand")),
                Sidecar.decodeAll(line),
                Probe.decode(line));
    }

    private static String orEmptyDeploy(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
