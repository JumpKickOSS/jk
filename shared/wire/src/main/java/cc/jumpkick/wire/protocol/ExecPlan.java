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
        return "{\"type\":\"" + EngineProtocol.EXEC_PLAN_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"mainIssue\":" + Jsonl.quote(mainIssue)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"argv\":" + EngineProtocol.quoteArray(argv)
                + ",\"workingDir\":" + Jsonl.quote(workingDir)
                + ",\"display\":" + Jsonl.quote(display)
                + ",\"javaHome\":" + Jsonl.quote(javaHome)
                + ",\"hotReload\":" + hotReload
                + ",\"devtoolsInjected\":" + devtoolsInjected
                + ",\"watchRoots\":" + EngineProtocol.quoteArray(watchRoots)
                + ",\"linkSrcs\":" + EngineProtocol.quoteArray(linkSrcs)
                + ",\"linkDests\":" + EngineProtocol.quoteArray(linkDests)
                + ",\"launcherPath\":" + Jsonl.quote(launcherPath)
                + ",\"launcherScript\":" + Jsonl.quote(launcherScript)
                + ",\"binPath\":" + Jsonl.quote(binPath)
                + ",\"boot\":" + boot
                + ",\"mainJar\":" + Jsonl.quote(mainJar)
                + ",\"tier\":" + Jsonl.quote(tier)
                + ",\"mainClass\":" + Jsonl.quote(mainClass)
                + ",\"libNames\":" + EngineProtocol.quoteArray(libNames)
                + ",\"libPaths\":" + EngineProtocol.quoteArray(libPaths)
                + ",\"deployCommand\":" + Jsonl.quote(deployCommand)
                + "}";
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
