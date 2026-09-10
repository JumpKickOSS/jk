// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
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
        List<Sidecar> sidecars) {

    /**
     * One sidecar the client is to run beside the app: {@code cwd} absolute, {@code env} the
     * values to lay over the inherited environment, probe fields as the manifest states them and
     * {@code restart} as its manifest spelling ({@code never} or {@code on-exit}).
     */
    public record Sidecar(
            String name,
            List<String> command,
            String cwd,
            Map<String, String> env,
            String ready,
            String readyPattern,
            long readyTimeoutMillis,
            boolean frontDoor,
            String restart) {

        String encode() {
            return JsonFields.object()
                    .string("name", name)
                    .array("command", command)
                    .string("cwd", cwd)
                    .map("env", env)
                    .string("ready", ready)
                    .string("readyPattern", readyPattern)
                    .number("readyTimeoutMillis", readyTimeoutMillis)
                    .bool("frontDoor", frontDoor)
                    .string("restart", restart)
                    .finish();
        }

        static Sidecar decode(String object) {
            return new Sidecar(
                    orEmpty(Jsonl.str(object, "name")),
                    Jsonl.strArray(object, "command"),
                    orEmpty(Jsonl.str(object, "cwd")),
                    Jsonl.strMap(object, "env"),
                    orEmpty(Jsonl.str(object, "ready")),
                    orEmpty(Jsonl.str(object, "readyPattern")),
                    Jsonl.longValue(object, "readyTimeoutMillis", 60_000L),
                    Jsonl.bool(object, "frontDoor", false),
                    orEmpty(Jsonl.str(object, "restart")));
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
                List.of());
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
                Sidecar.decodeAll(line));
    }

    private static String orEmptyDeploy(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
