// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The decision behind {@code jk agent-hook}, an agent's pre-tool-use hook: deny Maven and Gradle
 * (the project builds with jk) and nudge a symbol search toward the code graph, which answers it
 * completely. Anything else passes untouched, so grep and read stay available for text that is not a
 * symbol.
 */
public final class AgentHook {

    private static final Pattern SEGMENT_SPLIT = Pattern.compile("&&|\\|\\||[;&|\\n]");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern CAMEL_OR_QUALIFIED = Pattern.compile(".*[a-z0-9][A-Z].*|^[A-Z][a-z].*|.*\\..*");
    private static final List<String> SEARCHERS = List.of("grep", "egrep", "fgrep", "rg", "ag", "ack");
    private static final List<String> LEGACY_BUILDERS = List.of("mvn", "mvnw", "gradle", "gradlew");

    private AgentHook() {}

    /**
     * The hook's JSON reply for the tool call in {@code stdin}, or {@code null} to let it through
     * silently. Input that is not a tool call passes.
     */
    public static @Nullable String decide(String stdin) {
        Object call;
        try {
            call = MiniJson.parse(stdin);
        } catch (IllegalArgumentException notJson) {
            return null;
        }
        String tool = MiniJson.str(call, "tool_name");
        Object input = MiniJson.get(call, "tool_input");
        if (tool == null || input == null) return null;
        if (tool.equals("Bash")) {
            String command = MiniJson.str(input, "command");
            return command == null ? null : bash(command);
        }
        if (tool.equals("Grep")) {
            String pattern = MiniJson.str(input, "pattern");
            return pattern == null ? null : nudgeFor(symbol(pattern));
        }
        return null;
    }

    private static @Nullable String bash(String command) {
        for (String segment : SEGMENT_SPLIT.split(command)) {
            List<String> words = List.of(segment.strip().split("\\s+"));
            int at = 0;
            while (at < words.size() && words.get(at).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) at++;
            if (at >= words.size()) continue;
            String program = words.get(at);
            String leaf = program.substring(program.lastIndexOf('/') + 1);
            if (LEGACY_BUILDERS.contains(leaf)) return deny(leaf);
            boolean gitGrep =
                    leaf.equals("git") && words.subList(at + 1, words.size()).contains("grep");
            if (SEARCHERS.contains(leaf) || gitGrep) {
                for (String word : words.subList(at + 1, words.size())) {
                    if (word.startsWith("-") || word.equals("grep")) continue;
                    return nudgeFor(symbol(unquote(word)));
                }
            }
        }
        return null;
    }

    private static String unquote(String word) {
        if (word.length() >= 2) {
            char first = word.charAt(0);
            if ((first == '"' || first == '\'') && word.charAt(word.length() - 1) == first) {
                return word.substring(1, word.length() - 1);
            }
        }
        return word;
    }

    /** {@code pattern} as a bare symbol ({@code \bfooBar\(} included), or {@code null} when it is not one. */
    static @Nullable String symbol(String pattern) {
        String bare = pattern.replace("\\b", "").replaceAll("\\\\?\\($", "").strip();
        if (!SYMBOL.matcher(bare).matches()) return null;
        return CAMEL_OR_QUALIFIED.matcher(bare).matches() ? bare : null;
    }

    private static @Nullable String deny(String program) {
        return reply(
                "permissionDecision",
                "deny",
                "permissionDecisionReason",
                "This project builds with jk, not `" + program + "`. Use `jk -a test` or `jk -a build`;"
                        + " `jk add g:a` / `jk remove g:a` edit dependencies. `jk skill` has the rest.");
    }

    private static @Nullable String nudgeFor(@Nullable String symbol) {
        if (symbol == null) return null;
        return reply(
                "additionalContext",
                "`" + symbol + "` looks like a symbol. The code graph answers symbol questions completely, from"
                        + " the compiled classes, so use it instead of grepping and reading each match:"
                        + " `jk graph callers " + symbol + "` (who calls it; add -t for everything that reaches it),"
                        + " `jk graph read " + symbol + "` (its source alone),"
                        + " `jk graph rename " + symbol + " <new>` (every edit in one call). MCP: `callers`, `rename`."
                        + " Grep is still right for text that is not a symbol.",
                null,
                null);
    }

    private static String reply(String key, String value, @Nullable String key2, @Nullable String value2) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hookEventName", "PreToolUse");
        out.put(key, value);
        if (key2 != null) out.put(key2, value2);
        return MiniJson.write(Map.of("hookSpecificOutput", out));
    }
}
