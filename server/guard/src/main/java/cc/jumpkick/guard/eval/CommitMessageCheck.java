// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code commit} rules over one commit message — the {@code commit-msg} hook's entry point ({@code jk
 * guard commit-msg <file>}). Runs in no lane and keeps no baseline: a message is judged once, when
 * it is written. {@code pattern}/{@code patterns} must not match anywhere; {@code forbid-trailers}
 * are globs over the trailer lines ({@code Key: value}, case-insensitive); {@code require} regexes
 * must match. Each failure carries the rule's {@code instead} and {@code why}.
 */
public final class CommitMessageCheck {

    private static final Pattern TRAILER = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*):\\s+(.*)$");

    private CommitMessageCheck() {}

    /** @param problems one rendered failure per violation; {@code rules} the commit rules that ran */
    public record Result(List<String> problems, int rules) {
        public boolean ok() {
            return problems.isEmpty();
        }

        /** The failures as the CLI prints them, then a one-line verdict. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (String p : problems) sb.append(p).append("\n\n");
            if (rules == 0) sb.append("jk guard commit-msg: no commit rules in jk-guards.toml");
            else if (problems.isEmpty())
                sb.append("jk guard commit-msg: ")
                        .append(rules)
                        .append(rules == 1 ? " rule" : " rules")
                        .append(" · clean");
            else
                sb.append("jk guard commit-msg: ")
                        .append(problems.size())
                        .append(problems.size() == 1 ? " violation" : " violations")
                        .append(" — the commit was refused; fix the message per Instead");
            return sb.toString();
        }
    }

    public static Result check(RuleSet rules, String message) {
        List<String> problems = new ArrayList<>();
        int ran = 0;
        List<String> lines = message.lines().toList();
        for (String id : rules.ids()) {
            Rule r = rules.rules().get(id);
            if (r == null || r.kind() != Kind.COMMIT) continue;
            ran++;
            TomlTable t = r.table();
            List<String> patterns = new ArrayList<>();
            if (t.isString("pattern")) patterns.add(String.valueOf(t.getString("pattern")));
            patterns.addAll(strings(t.getArray("patterns")));
            for (String p : patterns) {
                Pattern compiled;
                try {
                    compiled = Pattern.compile(p);
                } catch (PatternSyntaxException e) {
                    problems.add(failure(r, 0, "pattern does not compile: " + e.getDescription()));
                    continue;
                }
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = compiled.matcher(lines.get(i));
                    if (m.find())
                        problems.add(failure(r, i + 1, "`" + lines.get(i).strip() + "` matches `" + p + "`"));
                }
            }
            List<String> forbid = strings(t.getArray("forbid-trailers"));
            if (!forbid.isEmpty()) {
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = TRAILER.matcher(lines.get(i));
                    if (!m.matches()) continue;
                    String trailer = lines.get(i).strip();
                    for (String glob : forbid) {
                        if (Rule.globMatches(glob.toLowerCase(Locale.ROOT), trailer.toLowerCase(Locale.ROOT)))
                            problems.add(failure(
                                    r, i + 1, "trailer `" + trailer + "` matches forbid-trailers `" + glob + "`"));
                    }
                }
            }
            for (String req : strings(t.getArray("require"))) {
                try {
                    if (!Pattern.compile(req).matcher(message).find())
                        problems.add(failure(r, 0, "the message does not match required `" + req + "`"));
                } catch (PatternSyntaxException e) {
                    problems.add(failure(r, 0, "require pattern does not compile: " + e.getDescription()));
                }
            }
        }
        return new Result(problems, ran);
    }

    private static String failure(Rule rule, int line, String detail) {
        StringBuilder sb = new StringBuilder("commit message");
        if (line > 0) sb.append(':').append(line);
        sb.append(": ").append(detail).append('\n');
        if (rule.instead() != null)
            sb.append("  Instead:  ").append(rule.instead()).append('\n');
        sb.append("  Why:      ").append(rule.why()).append('\n');
        sb.append("  Source:   ").append(rule.source().render()).append('\n');
        sb.append("  Explain:  jk guard explain ").append(rule.id());
        return sb.toString();
    }

    private static List<String> strings(@Nullable TomlArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        return out;
    }
}
