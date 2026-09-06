// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.CodeText;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code text}: a pattern over one view of the tree's text. One streaming pass: each file is read
 * once, projected once per view any rule asked for, every rule applied, then discarded. A code
 * projection is whitespace-squashed so a wrapped call cannot evade a one-line pattern; the match is
 * mapped back to its original line. Each regex runs against a deadline; an expiry is that rule's
 * {@code scanner-failed}, not a stall.
 *
 * <p>Bite is checked first: {@code hit} must match through the rule's own view (a {@code hit} the
 * blanker erases is a pattern looking at the wrong layer) and {@code miss} must not.
 */
final class TextEvaluator implements BatchEvaluator {

    static final long DEADLINE_MILLIS = 2_000;

    /** One rule, compiled. */
    private static final class Prepared {
        final Rule rule;
        final List<Pattern> patterns = new ArrayList<>();
        final CodeText.Blank blank;
        final List<String> files;
        final List<String> owners;
        final List<String> languages;
        final @Nullable Count count;
        final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        final List<Hit> hits = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();
        /** The {@code hit} snippet, once it has matched through the view: bite evidence on its own. */
        @Nullable
        String hit;

        long filesExamined;
        boolean ownerSeen;
        boolean ownerHasMatch;

        @Nullable
        String failure;

        Prepared(Rule rule) {
            TomlTable t = rule.table();
            this.rule = rule;
            this.blank = TextFiles.blankMode(t.isString("blank") ? t.getString("blank") : null);
            this.files = ForbidEvaluator.strings(t, "files");
            this.owners = ForbidEvaluator.strings(t, "owner");
            this.languages = ForbidEvaluator.strings(t, "languages");
            this.count = Count.parse(t.getTable("count"));
            for (Allow a : rule.allow()) allowUsed.put(a, false);
        }
    }

    private record Hit(String rel, int line, String snippet, boolean inOwner) {}

    /** {@code count = { exactly | min | max, per = file | tree | match }}. */
    record Count(
            @Nullable Long exactly,
            @Nullable Long min,
            @Nullable Long max,
            String per) {
        static @Nullable Count parse(@Nullable TomlTable t) {
            if (t == null) return null;
            String per = t.isString("per") ? String.valueOf(t.getString("per")) : "file";
            return new Count(t.getLong("exactly"), t.getLong("min"), t.getLong("max"), per);
        }

        @Nullable
        String verdict(long n) {
            if (exactly != null && n != exactly) return "expected exactly " + exactly + ", found " + n;
            if (min != null && n < min) return "expected at least " + min + ", found " + n;
            if (max != null && n > max) return "expected at most " + max + ", found " + n;
            return null;
        }
    }

    @Override
    public Map<String, Evaluation> evaluateAll(List<Rule> rules, EvalContext ctx) throws IOException {
        Map<String, Evaluation> out = new LinkedHashMap<>();
        List<Prepared> live = new ArrayList<>();
        for (Rule r : rules) {
            Prepared p = new Prepared(r);
            String bite = compileAndBite(p);
            if (bite != null) {
                out.put(r.id(), Evaluation.failed(bite));
                continue;
            }
            live.add(p);
        }
        if (live.isEmpty()) return out;

        Path moduleDir = ctx.moduleDir();
        Path root = ctx.lane() == Lane.MODULE && moduleDir != null ? moduleDir : ctx.root();
        for (TextFiles.Entry f : TextFiles.corpus(root)) {
            List<Prepared> applicable = new ArrayList<>();
            for (Prepared p : live) if (applies(p, f)) applicable.add(p);
            if (applicable.isEmpty()) continue;
            String text = TextFiles.read(f.file());
            if (text == null) continue;
            Map<CodeText.Blank, Object> views = new EnumMap<>(CodeText.Blank.class);
            for (Prepared p : applicable) {
                if (p.failure != null) continue;
                p.filesExamined++;
                boolean inOwner = matchesAny(p.owners, f.rel());
                if (inOwner) p.ownerSeen = true;
                if (!f.language().lexable && f.language().code && p.blank != CodeText.Blank.NONE) {
                    p.unsupported.add(f.rel());
                    continue;
                }
                Object view = views.computeIfAbsent(p.blank, mode -> project(text, mode, f.language()));
                try {
                    scan(p, f, text, view, inOwner);
                } catch (DeadlineCharSequence.Expired e) {
                    p.failure = e.getMessage() + " on " + f.rel();
                } catch (StackOverflowError e) {
                    p.failure = "regex overflowed the stack on " + f.rel();
                }
            }
        }
        for (Prepared p : live) out.put(p.rule.id(), finish(p, ctx));
        return out;
    }

    private static @Nullable String compileAndBite(Prepared p) {
        TomlTable t = p.rule.table();
        List<String> raw = new ArrayList<>();
        if (t.isString("pattern")) raw.add(String.valueOf(t.getString("pattern")));
        raw.addAll(ForbidEvaluator.strings(t, "patterns"));
        for (String r : raw) {
            try {
                p.patterns.add(Pattern.compile(r));
            } catch (PatternSyntaxException e) {
                return "pattern does not compile: " + e.getDescription() + " in `" + r + "`";
            }
        }
        if (p.patterns.isEmpty()) return "no pattern";
        String hit = t.isString("hit") ? t.getString("hit") : null;
        String miss = t.isString("miss") ? t.getString("miss") : null;
        p.hit = hit;
        if (hit != null) {
            Object view = project(hit, p.blank, TextFiles.Language.JAVA);
            if (!anyMatch(p.patterns, viewText(view))) {
                return "hit does not match through the `" + blankName(p.blank)
                        + "` view — the pattern is looking at the wrong layer, or the hit is not a hit";
            }
        }
        if (miss != null) {
            Object view = project(miss, p.blank, TextFiles.Language.JAVA);
            if (anyMatch(p.patterns, viewText(view))) return "miss matches: the pattern is wider than the rule says";
        }
        return null;
    }

    private static boolean applies(Prepared p, TextFiles.Entry f) {
        if (!p.languages.isEmpty() && !p.languages.contains(f.language().id())) return false;
        if (p.files.isEmpty()) return f.rel().contains("/src/") || f.rel().startsWith("src/");
        return matchesAny(p.files, f.rel());
    }

    static boolean matchesAny(List<String> globs, String rel) {
        for (String g : globs) if (Rule.globMatches(g, rel)) return true;
        return false;
    }

    /** The view a rule scans: for lexable code, a squashed projection; for prose and config, the text. */
    private static Object project(String text, CodeText.Blank mode, TextFiles.Language lang) {
        if (!lang.lexable) return text;
        String blanked = CodeText.blank(text, mode, lang == TextFiles.Language.JS);
        return mode == CodeText.Blank.CODE ? blanked : Squashed.of(blanked);
    }

    private static CharSequence viewText(Object view) {
        return view instanceof Squashed s ? s.text : (String) view;
    }

    private static boolean anyMatch(List<Pattern> patterns, CharSequence text) {
        for (Pattern p : patterns) if (p.matcher(text).find()) return true;
        return false;
    }

    private void scan(Prepared p, TextFiles.Entry f, String original, Object view, boolean inOwner) {
        CharSequence text = new DeadlineCharSequence(viewText(view), DEADLINE_MILLIS);
        for (Pattern pattern : p.patterns) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                if (m.end() == m.start()) {
                    if (m.end() >= text.length()) break;
                    continue;
                }
                int offset = view instanceof Squashed s ? s.originalOffset(m.start()) : m.start();
                String snippet = text.subSequence(m.start(), m.end()).toString();
                p.hits.add(new Hit(f.rel(), CodeText.lineAt(original, offset), snippet, inOwner));
            }
        }
    }

    private static Evaluation finish(Prepared p, EvalContext ctx) {
        if (p.failure != null) return Evaluation.failed(p.failure);
        Map<String, Long> population = Map.of("files", p.filesExamined);
        if (!p.unsupported.isEmpty()) {
            return new Evaluation(
                    Outcome.UNSUPPORTED,
                    population,
                    List.of(),
                    p.unsupported.size()
                            + " file(s) in a language the blanker cannot lex; use `blank = \"none\"` or exclude them with `languages`/`files`: "
                            + p.unsupported.get(0) + (p.unsupported.size() > 1 ? " …" : ""));
        }
        if (p.filesExamined == 0) return Evaluation.of(population, List.of());
        for (Hit h : p.hits) if (h.inOwner()) p.ownerHasMatch = true;
        if (!p.owners.isEmpty()) {
            if (!p.ownerSeen)
                return Evaluation.ownerMissing("owner " + String.join(", ", p.owners) + " matched no file");
            if (!p.ownerHasMatch)
                return Evaluation.ownerMissing("owner " + String.join(", ", p.owners)
                        + " no longer contains the pattern; the rule points callers at nothing");
        }
        List<Observation> observations = p.count == null ? banObservations(p) : countObservations(p);
        List<String> stale = new ArrayList<>();
        for (var e : p.allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    observations,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, observations).withBite(p.hit != null || !p.hits.isEmpty());
    }

    /** Without {@code count}: every match outside the owner is a violation. */
    private static List<Observation> banObservations(Prepared p) {
        List<Observation> out = new ArrayList<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (Hit h : p.hits) {
            if (h.inOwner()) continue;
            Allow a = allowing(p, h.rel());
            if (a != null) {
                p.allowUsed.put(a, true);
                continue;
            }
            String squashed = h.snippet().replaceAll("\\s+", " ").strip();
            String base = h.rel() + " | " + Hashing.sha256Hex(squashed).substring(0, 16);
            int ordinal = ordinals.merge(base, 1, Integer::sum);
            out.add(Observation.site(base + " | " + ordinal, h.rel(), h.line(), "`" + abbreviate(squashed) + "`"));
        }
        return out;
    }

    /** With {@code count}: the unit whose count is off is the violation. */
    private static List<Observation> countObservations(Prepared p) {
        Count c = p.count;
        if (c == null) return List.of();
        List<Observation> out = new ArrayList<>();
        Map<String, List<Hit>> byUnit = new TreeMap<>();
        for (Hit h : p.hits) {
            if (h.inOwner()) continue;
            Allow a = allowing(p, h.rel());
            if (a != null) {
                p.allowUsed.put(a, true);
                continue;
            }
            String unit =
                    switch (c.per()) {
                        case "match" -> h.snippet().replaceAll("\\s+", " ").strip();
                        case "tree" -> "tree";
                        default -> h.rel();
                    };
            byUnit.computeIfAbsent(unit, k -> new ArrayList<>()).add(h);
        }
        if (c.per().equals("tree") && byUnit.isEmpty()) byUnit.put("tree", List.of());
        for (var e : byUnit.entrySet()) {
            String verdict = c.verdict(e.getValue().size());
            if (verdict == null) continue;
            Hit first = e.getValue().isEmpty() ? null : e.getValue().get(0);
            String where = first == null ? null : first.rel();
            out.add(Observation.site(
                    "count | " + e.getKey(),
                    where,
                    first == null ? 0 : first.line(),
                    (c.per().equals("match") ? "`" + abbreviate(e.getKey()) + "`" : e.getKey()) + ": " + verdict));
        }
        return out;
    }

    private static @Nullable Allow allowing(Prepared p, String rel) {
        for (Allow a : p.rule.allow()) {
            if (Rule.globMatches(a.in(), rel) || rel.equals(a.in()) || rel.startsWith(a.in() + "/")) return a;
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private static String blankName(CodeText.Blank b) {
        return switch (b) {
            case COMMENTS -> "comments";
            case COMMENTS_AND_STRINGS -> "comments+strings";
            case NONE -> "none";
            case CODE -> "code";
        };
    }
}
