// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
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
        String deployCommand) {

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
                "");
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
                orEmptyDeploy(Jsonl.str(line, "deployCommand")));
    }

    private static String orEmptyDeploy(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
