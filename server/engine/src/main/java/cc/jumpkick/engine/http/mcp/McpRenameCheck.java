// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After an MCP {@code rename}, the check its answer names: the edited tests, then guard, run here
 * within the caller's wait, so the answer says whether the task is done. Agents otherwise spend a
 * tool call (and, on some hosts, a schema lookup) on each.
 */
public final class McpRenameCheck {

    /** The answer's test instruction: {@code run(kind=test, only=a,b, classes=X,Y)}. */
    private static final Pattern TEST_CALL = Pattern.compile("run\\(kind=test((?:, [a-z]+=[^,) ]+(?:,[^,) =]+)*)*)\\)");

    /** The answer's sentence that names the check, from {@code Test} to the end of its line. */
    private static final Pattern TEST_LINE = Pattern.compile("(?m) ?Test (?:next|the [a-z ]+edited):.*$");

    /** Seconds a guard needs left in the budget to be worth starting here. */
    private static final int GUARD_FLOOR_S = 5;

    private McpRenameCheck() {}

    /**
     * {@code renamed} followed by its check's verdicts. An answer with no test instruction (refused,
     * nothing renamed, modules not checked) is returned as it is.
     */
    public static Map<String, Object> after(McpCall in, String renamed, long startedNanos) {
        Matcher call = TEST_CALL.matcher(renamed);
        if (!call.find()) return in.text(renamed);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("dir", in.requiredDir());
        args.put("kind", "test");
        for (String pair : call.group(1).split(", ")) {
            int eq = pair.indexOf('=');
            if (eq > 0)
                args.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
        int budget = McpJobRuns.timeoutS(in);
        int left = budget - elapsedS(startedNanos);
        if (left < 1) return in.text(renamed);
        String body = TEST_LINE.matcher(renamed).replaceAll("").stripTrailing();
        String test = verdict(McpJobRuns.run(sub(in, args, left), null));
        if (!test.startsWith("OK")) {
            return in.text(body + "\nChecked by jk, the edited tests: " + test.strip() + "\n" + call.group()
                    + " runs them again; then run(kind=guard).");
        }
        left = budget - elapsedS(startedNanos);
        if (left < GUARD_FLOOR_S) {
            return in.text(body + "\nChecked by jk: " + firstLine(test)
                    + ". Then run(kind=guard); when it says OK the task is done.");
        }
        // Guard the modules the rename edited, as the tests ran: a workspace's whole guard can take minutes.
        Map<String, Object> guardArgs = new LinkedHashMap<>(Map.of("dir", in.requiredDir(), "kind", "guard"));
        if (args.containsKey("only")) guardArgs.put("only", args.get("only"));
        String guard = verdict(McpJobRuns.run(sub(in, guardArgs, left), null));
        if (!guard.startsWith("OK")) {
            return in.text(body + "\nChecked by jk: " + firstLine(test) + ". Guard: " + guard.strip());
        }
        return in.text(body + "\nChecked by jk: " + firstLine(test) + "; " + firstLine(guard)
                + ". The task is done: no other build, test or search is needed.");
    }

    private static McpCall sub(McpCall in, Map<String, Object> args, int timeoutS) {
        Map<String, Object> withWait = new LinkedHashMap<>(args);
        withWait.put("timeout_s", timeoutS);
        return new McpCall(in.ctx(), withWait, in.progressToken(), in.connection());
    }

    /** The text of a {@code tools/call} result. */
    static String verdict(Map<String, Object> result) {
        if (result.get("content") instanceof List<?> content
                && !content.isEmpty()
                && content.getFirst() instanceof Map<?, ?> first) {
            return String.valueOf(first.get("text"));
        }
        return "";
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).strip();
    }

    private static int elapsedS(long startedNanos) {
        return (int) ((Clock.SYSTEM.nanos() - startedNanos) / 1_000_000_000L);
    }
}
