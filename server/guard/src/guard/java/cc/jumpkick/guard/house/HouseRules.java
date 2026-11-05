// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Allow;
import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.MetricSite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.OwnerMissing;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jk's house rules that no closed vocabulary should try to express: composite path expressions, a
 * both-sides truth set with a per-file ratchet, exemption by spec, per-module schema arms, an
 * ordered pair of predicates. Each reads the text the way the gate script did — comments blanked,
 * imports and package lines dropped — and reports the site; the engine owns the baseline, the
 * {@code code} and the rendering.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class HouseRules {

    private static final String MAIN_JAVA = "**/src/main/java/**/*.java";
    private static final String JDK_FINGERPRINT = "shared/host/src/main/java/cc/jumpkick/jdk/JdkFingerprint.java";
    private static final List<String> JSON_CODEC_OWNERS = List.of(
            "shared/host/src/main/java/cc/jumpkick/jsonl/Jsonl.java",
            "shared/host/src/main/java/cc/jumpkick/jsonl/MiniJson.java");
    private static final String CHAR_LITERAL = "'(?:\\\\.|[^\\\\'])'";
    private static final Pattern CASE_LABELS =
            Pattern.compile("(?<![\\w$])case\\s*((?:" + CHAR_LITERAL + "\\s*,\\s*)*" + CHAR_LITERAL + ")\\s*(?:->|:)");
    private static final Pattern CHAR = Pattern.compile(CHAR_LITERAL);
    private static final Pattern JSON_OBJECT_LITERAL = Pattern.compile("\\\\\"[A-Za-z_][A-Za-z0-9_.\\-]*\\\\\"\\s*:");

    /** The text a guard pattern is matched against: comments blanked, import and package lines gone. */
    private static String code(Text text, String path) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.blanked(path, Blank.COMMENTS).split("\n", -1)) {
            String s = line.stripLeading();
            sb.append(s.startsWith("import ") || s.startsWith("package ") ? "" : line)
                    .append('\n');
        }
        return sb.toString();
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) if (code.charAt(i) == '\n') line++;
        return line;
    }

    /**
     * An owner's text with its comments blanked, the lexer chosen by the file's kind: {@code #} line
     * comments for a YAML workflow, the C family otherwise. One view per kind, picked here, so a
     * guard never matches a workflow comment as text or loses the {@code //} of a URL in a
     * {@code run:} script to a line-comment rule.
     */
    static String owner(Text text, String path) {
        try {
            return isYaml(path) ? yamlComments(text.lines(path)) : text.blanked(path, Blank.COMMENTS);
        } catch (RuntimeException gone) {
            throw new OwnerMissing("the owner " + path
                    + " is gone, so this guard reads nothing; restore it or retire the guard deliberately");
        }
    }

    private static boolean isYaml(String path) {
        return path.endsWith(".yml") || path.endsWith(".yaml");
    }

    /**
     * YAML with its {@code #} comments blanked to spaces: a whole-line comment and everything from
     * {@code " #"} on; lines and offsets kept. Line-oriented on purpose — a {@code #} inside a quoted
     * scalar reads as a comment — which is enough for the house guards, none of which parse a value.
     */
    static String yamlComments(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            int comment = line.strip().startsWith("#") ? line.indexOf('#') : line.indexOf(" #");
            if (comment < 0) out.append(line);
            else out.append(line, 0, comment).append(" ".repeat(line.length() - comment));
            out.append('\n');
        }
        return out.toString();
    }

    private static Set<String> caseLabelChars(String code) {
        Set<String> out = new LinkedHashSet<>();
        Matcher arm = CASE_LABELS.matcher(code);
        while (arm.find()) {
            Matcher c = CHAR.matcher(arm.group(1));
            while (c.find()) {
                String v = c.group();
                out.add(v.substring(1, v.length() - 1));
            }
        }
        return out;
    }

    @Guard(
            id = "hand-built-java-binary",
            why = "a hand-built <javaHome>/bin/java drops the Windows .exe and the fork is dead there",
            instead =
                    "JdkFingerprint.java(javaHome), .javac(javaHome), or .tool(javaHome, name) — on :host, so every module reaches it")
    @Fixture("server/guard/fixtures/hand-built-java-binary")
    void handBuiltJavaBinary(Text text, Violations v) {
        String ownerText = owner(text, JDK_FINGERPRINT);
        List<String> names = new ArrayList<>();
        Matcher nm =
                Pattern.compile("public static Path (\\w+)\\(Path javaHome\\)").matcher(ownerText);
        while (nm.find()) names.add(nm.group(1));
        if (names.isEmpty())
            throw new OwnerMissing(
                    "JdkFingerprint declares no `public static Path <name>(Path javaHome)` shorthand, so the ban list is gone");
        Matcher sm = Pattern.compile("tool \\+ \"([^\"]+)\"").matcher(ownerText);
        if (!sm.find())
            throw new OwnerMissing(
                    "JdkFingerprint.toolName no longer appends a literal suffix, so the rule it enforces is gone");
        String suffix = sm.group(1);
        List<String> banned = new ArrayList<>();
        for (String n : names) {
            banned.add("resolve(\"bin/" + n + "\")");
            banned.add("resolve(\"bin\").resolve(\"" + n + "\")");
            banned.add("resolve(\"bin\").resolve(\"" + n + suffix + "\")");
            banned.add(",\"bin\",\"" + n + "\"");
            banned.add(",\"bin\",\"" + n + suffix + "\"");
            banned.add("\"" + n + suffix + "\":\"" + n + "\"");
            banned.add("\"" + n + "\":\"" + n + suffix + "\"");
        }
        long files = 0;
        for (String rel : text.files(MAIN_JAVA)) {
            if (rel.equals(JDK_FINGERPRINT)) continue;
            files++;
            String code = code(text, rel).replace(" ", "");
            for (String b : banned) {
                int at = code.indexOf(b);
                while (at >= 0) {
                    v.add(new TextSite(rel, lineOf(code, at), b), "hand-built JDK tool path " + b);
                    at = code.indexOf(b, at + b.length());
                }
            }
        }
        v.population(files);
    }

    private static final Pattern VIRTUAL_STARTER = Pattern.compile(
            "SessionContext\\.startVirtual\\(|Thread\\.ofVirtual\\(\\)(?:\\s*\\.[A-Za-z]+\\([^()]*\\))*\\s*\\.(?:start|unstarted)\\(");
    private static final Pattern PIPE_READ =
            Pattern.compile("\\.(?:getInputStream|getErrorStream|inputReader|errorReader)\\([^()]*\\)");

    @Guard(
            id = "pipe-reader-platform-thread",
            why =
                    "a virtual thread parked in a Process pipe read holds its carrier for as long as the child is silent, and under one carrier every other virtual thread of the JVM waits behind it",
            instead =
                    "SessionContext.startPlatform, or Thread.ofPlatform().daemon(), for a body that reads a Process's getInputStream / getErrorStream / inputReader / errorReader")
    @Fixture("server/guard/fixtures/pipe-reader-platform-thread")
    void pipeReaderPlatformThread(Text text, Violations v) {
        long files = 0;
        for (String rel : text.files(MAIN_JAVA)) {
            files++;
            String code = text.blanked(rel, Blank.COMMENTS_AND_STRINGS);
            Matcher m = VIRTUAL_STARTER.matcher(code);
            while (m.find()) {
                if (PIPE_READ.matcher(callArguments(code, m.end() - 1)).find()) {
                    v.add(
                            new TextSite(rel, lineOf(code, m.start()), m.group()),
                            "reads a Process pipe on a virtual thread");
                }
            }
        }
        v.population(files);
    }

    /** The text between the {@code (} at {@code open} and its matching {@code )}; to the end when unbalanced. */
    private static String callArguments(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return code.substring(open + 1, i);
        }
        return code.substring(open + 1);
    }

    @Guard(
            id = "single-truth-set",
            why =
                    "jk has one boolean truth set, EnvValues.parseBool: 1/true/yes/on against 0/false/no/off, trimmed, case-insensitive",
            instead =
                    "cc.jumpkick.config.EnvValues.parseBool(raw) (or .bool(env, name) for a JK_* variable); a reader of someone else's format is a baseline entry that says so")
    @Fixture("server/guard/fixtures/single-truth-set")
    void singleTruthSet(Text text, Violations v) {
        String truthy = "true|1|yes|on|false|0|no|off";
        Pattern handRolled = Pattern.compile("\"(?:" + truthy
                + ")\"\\.equals(?:IgnoreCase)?\\(|\\.equals(?:IgnoreCase)?\\(\"(?:" + truthy + ")\"\\)");
        long files = 0;
        for (String rel : text.files(MAIN_JAVA)) {
            files++;
            Matcher m = handRolled.matcher(code(text, rel));
            int n = 0;
            while (m.find()) n++;
            // every file with a hit is a metric unit: the baseline holds the ratchet, growth is red
            if (n > 0) v.metric(new MetricSite(rel, n, rel), n + " hand-rolled truth comparison(s)");
        }
        v.population(files);
    }

    @Guard(
            id = "one-json-codec",
            why =
                    "jk escapes and parses JSON in one place, Jsonl and MiniJson; a second escaper agrees today and disagrees after one bug fix",
            instead =
                    "Jsonl.quote for one string, MiniJson.write / writePretty for a document, MiniJson.parse to read; a different format keeps its own owner (MinimalToml, MinimalXml)")
    void oneJsonCodec(Text text, Violations v) {
        String ownerCode = code(text, JSON_CODEC_OWNERS.get(0));
        Matcher um = Pattern.compile("String\\.format\\((\"[^\"]*u%04[xX]\")").matcher(ownerCode);
        String unicodeEscape = um.find() ? um.group(1) : null;
        Set<String> ownerEscapes = caseLabelChars(ownerCode);
        if (unicodeEscape == null || !ownerEscapes.containsAll(List.of("/", "u", "n"))) {
            throw new OwnerMissing(
                    "Jsonl no longer yields the escape alphabet this guard reads from it: unicode fallback "
                            + (unicodeEscape == null ? "MISSING" : unicodeEscape) + ", decoded escapes "
                            + ownerEscapes);
        }
        Pattern unicode = Pattern.compile(Pattern.quote(unicodeEscape), Pattern.CASE_INSENSITIVE);
        Set<String> decodesJson = new LinkedHashSet<>(ownerEscapes);
        decodesJson.remove("/");
        long files = 0;
        for (String rel : text.files(MAIN_JAVA)) {
            if (JSON_CODEC_OWNERS.contains(rel)) continue;
            files++;
            String code = code(text, rel);
            Set<String> labels = caseLabelChars(code);
            if (unicode.matcher(code).find()
                    && labels.contains("\"")
                    && JSON_OBJECT_LITERAL.matcher(code).find()) {
                v.add(new TextSite(rel, 0, "json writer"), "assembles a JSON object with an escaper of its own");
            }
            int decoded = 0;
            for (String l : labels) if (decodesJson.contains(l)) decoded++;
            if (labels.contains("/") && decoded >= 4) {
                v.add(new TextSite(rel, 0, "json reader"), "decodes JSON's escape alphabet itself");
            }
        }
        v.population(files);
    }

    @Guard(
            id = "plugin-family",
            why =
                    "a plugin module is one worker speaking one protocol: its family (SPI plugin with a jk-plugin.toml, or forked worker) must agree with its wire-prefix wiring, and an SPI plugin reads only keys its [schema] declares",
            instead =
                    "declare the prefix in jk-plugin.toml [code].protocol-prefix and never in server/*, or fork the worker from the engine and ship no descriptor; declare every config key under [schema]")
    void pluginFamily(Model model, Text text, Violations v) {
        List<String> engine = text.files("server/*/src/main/java/**/*.java");
        if (engine.size() < 350)
            throw new IllegalStateException("scanned " + engine.size()
                    + " engine Java files; measured against 425 — the walk stopped seeing server/*/src/main/java");
        Pattern quotedPrefix = Pattern.compile("\"(##JK[A-Z]+:)\"");
        String variants = owner(text, "shared/jk-api/src/main/java/cc/jumpkick/model/Variants.java");
        Matcher bt =
                Pattern.compile("String\\s+BUILD_TYPE\\s*=\\s*\"([^\"]+)\"").matcher(variants);
        if (!bt.find())
            throw new OwnerMissing(
                    "Variants no longer declares String BUILD_TYPE: the owner of the injected config keys is gone");
        String buildType = bt.group(1);
        String variantApply = owner(text, "shared/core/src/main/java/cc/jumpkick/plugin/manifest/VariantApply.java");
        Matcher vp = Pattern.compile("\"(variant\\.)\"\\s*\\+").matcher(variantApply);
        if (!vp.find())
            throw new OwnerMissing(
                    "VariantApply no longer builds a \"variant.\" + dimension config key: the second injected shape is gone");
        String variantPrefix = vp.group(1);
        Map<String, Set<String>> enginePrefixes = new LinkedHashMap<>();
        for (String rel : engine) {
            Matcher m = quotedPrefix.matcher(text.blanked(rel, Blank.COMMENTS));
            while (m.find())
                enginePrefixes
                        .computeIfAbsent(m.group(1), k -> new LinkedHashSet<>())
                        .add(rel);
        }
        long plugins = 0;
        for (String module : model.modules()) {
            if (!module.startsWith("plugins/")) continue;
            plugins++;
            List<String> files = text.files(module + "/src/main/java/**/*.java");
            String manifest = module + "/jk.toml";
            if (files.isEmpty()) {
                v.add(
                        new TextSite(manifest, 0, "no sources"),
                        module + ": no Java source under src/main/java, so the family guard verified nothing for it");
                continue;
            }
            Map<String, Set<String>> declared = new TreeMap<>();
            for (String rel : files) {
                Matcher m = quotedPrefix.matcher(text.blanked(rel, Blank.COMMENTS));
                while (m.find())
                    declared.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>())
                            .add(rel);
            }
            if (declared.size() != 1) {
                v.add(
                        new TextSite(manifest, 0, "prefixes " + declared.keySet()),
                        module + " must declare exactly one `##JK*:` wire prefix in its own src/main/java; found "
                                + declared.size() + " " + declared);
                continue;
            }
            String prefix = declared.keySet().iterator().next();
            Set<String> engineSites = enginePrefixes.getOrDefault(prefix, Set.of());
            String descriptor = module + "/jk-plugin.toml";
            List<String> descriptorLines;
            try {
                descriptorLines = text.lines(descriptor);
            } catch (RuntimeException absent) {
                descriptorLines = null;
            }
            if (descriptorLines != null) {
                StringBuilder codeSb = new StringBuilder();
                for (String l : descriptorLines)
                    if (!l.stripLeading().startsWith("#")) codeSb.append(l).append('\n');
                String desc = codeSb.toString();
                Matcher st =
                        Pattern.compile("protocol-prefix\\s*=\\s*\"([^\"]+)\"").matcher(desc);
                String stated = st.find() ? st.group(1) : null;
                if (!prefix.equals(stated)) {
                    v.add(
                            new TextSite(descriptor, 0, "protocol-prefix"),
                            module + " ships a jk-plugin.toml whose [code].protocol-prefix says "
                                    + (stated == null ? "nothing" : stated) + " while the code says " + prefix);
                    continue;
                }
                if (!engineSites.isEmpty()) {
                    v.add(
                            new TextSite(descriptor, 0, "engine names " + prefix),
                            module
                                    + " is an SPI plugin, so its prefix belongs to the descriptor; these engine sources spell it: "
                                    + engineSites);
                    continue;
                }
                Set<String> schemaKeys = new LinkedHashSet<>();
                boolean inSchema = false;
                for (String raw : desc.split("\n")) {
                    String t = raw.strip();
                    if (t.startsWith("[")) {
                        inSchema = t.equals("[schema]");
                        Matcher sub =
                                Pattern.compile("^\\[sub-schema\\.([^\\]]+)]$").matcher(t);
                        if (sub.find()) schemaKeys.add(sub.group(1));
                    } else if (inSchema) {
                        Matcher k = Pattern.compile("^([A-Za-z0-9._-]+)\\s*=").matcher(t);
                        if (k.find()) schemaKeys.add(k.group(1));
                    }
                }
                if (schemaKeys.isEmpty()) {
                    v.add(
                            new TextSite(descriptor, 0, "[schema]"),
                            module
                                    + "'s jk-plugin.toml declares no [schema] keys, so the totality arm verified nothing");
                    continue;
                }
                Map<String, Set<String>> reads = new TreeMap<>();
                Pattern accessor =
                        Pattern.compile("\\.(?:string|stringOpt|bool|stringList|group|intValue)\\(\\s*\"([^\"]+)\"");
                for (String rel : files) {
                    Matcher m = accessor.matcher(text.blanked(rel, Blank.COMMENTS));
                    while (m.find())
                        reads.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>())
                                .add(rel);
                }
                Matcher cond = Pattern.compile("config\\s*=\\s*\"([^\"]+)\"").matcher(desc);
                while (cond.find())
                    reads.computeIfAbsent(cond.group(1), k -> new LinkedHashSet<>())
                            .add(descriptor);
                Matcher interp = Pattern.compile("\\$\\{config\\.([^}]+)}").matcher(desc);
                while (interp.find())
                    reads.computeIfAbsent(interp.group(1), k -> new LinkedHashSet<>())
                            .add(descriptor);
                for (var e : reads.entrySet()) {
                    String key = e.getKey();
                    String top = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
                    if (top.equals(buildType) || key.startsWith(variantPrefix) || schemaKeys.contains(top)) continue;
                    v.add(
                            new TextSite(descriptor, 0, "undeclared " + key),
                            module + " reads config key `" + key + "` its [schema] does not declare (read at "
                                    + e.getValue() + "; declared " + schemaKeys + ")");
                }
            } else if (engineSites.isEmpty()) {
                v.add(
                        new TextSite(manifest, 0, "unreachable " + prefix),
                        module
                                + " ships no jk-plugin.toml, so it is a forked worker, but no source under server/*/src/main/java spells "
                                + prefix
                                + ": the worker is unreachable, or it has become an SPI plugin and the hardcoded fork is the thing to delete");
            }
        }
        v.population(plugins);
    }

    @Guard(
            id = "cheapest-rejection-first",
            why = "a stat before a free name test spends a syscall on every entry the name test was going to reject",
            instead = "put the string predicate first; Files::isRegularFile re-resolves the path for a fresh stat")
    void cheapestRejectionFirst(Text text, Violations v) {
        Pattern nameOnly = Pattern.compile("getFileName|endsWith\\(|startsWith\\(|\\.equals\\(");
        long files = 0;
        for (String rel : text.files(MAIN_JAVA)) {
            files++;
            List<String> lines = text.lines(rel);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).stripTrailing().endsWith(".filter(Files::isRegularFile)")) continue;
                int j = i + 1;
                while (j < lines.size()
                        && (lines.get(j).isBlank() || lines.get(j).strip().startsWith("//"))) j++;
                if (j >= lines.size()) continue;
                String next = lines.get(j).strip();
                if (!next.startsWith(".filter(")
                        || next.contains("Files.")
                        || !nameOnly.matcher(next).find()) continue;
                v.add(new TextSite(rel, i + 1, next), "a stat runs before a free name test: " + next);
            }
        }
        v.population(files);
    }

    /** A javadoc that says the declaration under it throws {@code UserConfigParseException}. */
    private static final Pattern THROWS_CONFIG_ERROR =
            Pattern.compile("/\\*\\*(?:(?!\\*/).)*?@throws\\s+UserConfigParseException\\b.*?\\*/", Pattern.DOTALL);
    /** The name such a declaration declares: annotations skipped, the identifier before its {@code (}. */
    private static final Pattern DECLARED_NAME =
            Pattern.compile("\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*[^;{=(]*?(\\w+)\\s*\\(");

    private static final Pattern METHOD_HEADER =
            Pattern.compile("(\\w+)\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*(?:throws\\s+[\\w.\\s,]+)?$");
    private static final Pattern NOT_A_METHOD = Pattern.compile("=|->|\\b(?:class|record|interface|enum|new)\\b");
    private static final Pattern TYPE_HEADER = Pattern.compile("(?<![\\w$.])(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Set<String> STATEMENT_KEYWORDS =
            Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "return", "else", "do", "throw");

    private static final Pattern TRY = Pattern.compile("(?<![\\w$])try\\s*");
    private static final Pattern CATCH = Pattern.compile("\\G\\s*catch\\s*\\(");
    private static final Pattern BROAD =
            Pattern.compile("(?<![\\w$.])(?:Exception|RuntimeException|Throwable)(?![\\w$])");
    private static final Pattern NARROW_CONFIG =
            Pattern.compile("(?<![\\w$])(?:UserConfigParseException|JkBuildParseException)(?![\\w$])");

    /** A method a call can name: its file's class, its own name, and the file that declares it. */
    private record Callee(String owner, String name, String file) {}

    /** A method or type body in blanked code: its name and the offsets of its braces. */
    private record Method(String name, int open, int close) {}

    @Guard(
            id = "config-error-through-broad-catch",
            why =
                    "a catch (Exception) around a read that throws UserConfigParseException, or around a same-module method that calls one, turns a broken ~/.jk/config.toml into a silent fallback, and the user never learns which line to fix",
            instead =
                    "put `catch (UserConfigParseException unparsed) { throw unparsed; }` ahead of the broad catch, or render the caught exception's message (Errors.text(e), e.getMessage(), requestFailedLine(dir, e)): it carries file:line:col")
    @Fixture("server/guard/fixtures/config-error-through-broad-catch")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/engine/SsePublisher.java",
            reason = "the dashboard's coordinate for a starting request; the request's own parse reports the file")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/engine/journal/JournalWriter.java",
            reason = "the journal's coordinate for a run; the run's own parse reports the file")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/HardwareProbe.java",
            reason =
                    "a probe that stays off the network when the repositories cannot be read; the resolve names the file")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/LockFlow.java",
            reason = "a hint built after a failed resolve; it must never replace the resolve's own error")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/TestLaunch.java",
            reason = "a once-per-engine warm-up of the test roots; the test run's own parse reports the file")
    @Allow(
            in = "shared/core/src/main/java/cc/jumpkick/templates/OfficialTemplatesFreshen.java",
            reason = "a background freshen that never throws; its log line carries the message and the position")
    void configErrorThroughBroadCatch(Text text, Violations v) {
        List<String> files = text.files(MAIN_JAVA);
        Map<String, String> codes = new LinkedHashMap<>();
        for (String rel : files) codes.put(rel, text.blanked(rel, Blank.COMMENTS_AND_STRINGS));
        // The reads: every method whose javadoc declares the throw. No list to keep: a new strict
        // reader is followed as soon as it says what it throws.
        List<Callee> doors = new ArrayList<>();
        for (String rel : files) {
            String raw = text.blanked(rel, Blank.NONE);
            if (!raw.contains("UserConfigParseException")) continue;
            List<Method> types = types(codes.get(rel));
            Matcher doc = THROWS_CONFIG_ERROR.matcher(raw);
            while (doc.find()) {
                Matcher name = DECLARED_NAME.matcher(raw).region(doc.end(), raw.length());
                if (name.lookingAt()) doors.add(new Callee(ownerAt(types, doc.end(), rel), name.group(1), rel));
            }
        }
        if (doors.isEmpty())
            throw new OwnerMissing(
                    "no method declares `@throws UserConfigParseException`, so this guard knows no read to follow");
        // One hop: a method that calls a read is a read to a caller in its own module.
        List<Callee> wrappers = new ArrayList<>();
        for (String rel : files) {
            String code = codes.get(rel);
            Pattern direct = calls(doors, List.of(), rel);
            if (!direct.matcher(code).find()) continue;
            List<Method> types = types(code);
            for (Method m : methods(code)) {
                if (direct.matcher(code).region(m.open(), m.close()).find())
                    wrappers.add(new Callee(ownerAt(types, m.open(), rel), m.name(), rel));
            }
        }
        long tries = 0;
        for (String rel : files) {
            String code = codes.get(rel);
            if (!code.contains("catch")) continue;
            Pattern reach = calls(doors, wrappers, rel);
            Matcher t = TRY.matcher(code);
            while (t.find()) {
                int open = t.end();
                if (open < code.length() && code.charAt(open) == '(')
                    open = skipSpace(code, closing(code, open, '(', ')') + 1);
                if (open >= code.length() || code.charAt(open) != '{') continue;
                int close = closing(code, open, '{', '}');
                Matcher door = reach.matcher(code).region(open, close);
                if (!door.find()) continue;
                tries++;
                String read = door.group().replaceAll("\\s*\\($", "").replaceAll("\\s+", "");
                boolean through = false;
                int at = close + 1;
                Matcher c = CATCH.matcher(code);
                while (c.find(at) && c.start() == at) {
                    int paren = c.end() - 1;
                    int parenEnd = closing(code, paren, '(', ')');
                    String types = code.substring(paren + 1, parenEnd);
                    int body = code.indexOf('{', parenEnd);
                    if (body < 0) break;
                    int bodyEnd = closing(code, body, '{', '}');
                    String handler = code.substring(body, bodyEnd + 1);
                    String var = types.strip().replaceAll(".*\\s", "");
                    if (NARROW_CONFIG.matcher(types).find()) {
                        through = true;
                    } else if (BROAD.matcher(types).find() && !through) {
                        if (handlesConfigError(handler, var)) {
                            if (rethrows(handler, var)) through = true;
                        } else {
                            String site = "catch (" + types.strip() + ") around " + read;
                            v.add(
                                    new TextSite(rel, lineOf(code, c.start()), site),
                                    site + " neither lets UserConfigParseException through nor renders its message");
                        }
                    }
                    at = bodyEnd + 1;
                }
            }
        }
        v.population(tries);
    }

    /**
     * A call of a read or of a wrapper in {@code rel}'s module: {@code Owner.name(} anywhere, a bare
     * {@code name(} in the file that declares it.
     */
    private static Pattern calls(List<Callee> doors, List<Callee> wrappers, String rel) {
        String module = moduleOf(rel);
        Set<String> alternatives = new LinkedHashSet<>();
        for (Callee d : doors) addCall(alternatives, d, rel);
        for (Callee w : wrappers) if (moduleOf(w.file()).equals(module)) addCall(alternatives, w, rel);
        return Pattern.compile("(?:" + String.join("|", alternatives) + ")\\s*\\(");
    }

    private static void addCall(Set<String> alternatives, Callee c, String rel) {
        alternatives.add("(?<![\\w$])" + Pattern.quote(c.owner()) + "\\s*\\.\\s*" + Pattern.quote(c.name()));
        if (c.file().equals(rel)) alternatives.add("(?<![\\w$.])" + Pattern.quote(c.name()));
    }

    /** Every brace whose header reads as a method or constructor declaration, lambdas and statements left out. */
    private static List<Method> methods(String code) {
        List<Method> out = new ArrayList<>();
        for (int i = code.indexOf('{'); i >= 0; i = code.indexOf('{', i + 1)) {
            int from = Math.max(
                    code.lastIndexOf(';', i - 1), Math.max(code.lastIndexOf('{', i - 1), code.lastIndexOf('}', i - 1)));
            String header = code.substring(from + 1, i).strip();
            if (header.isEmpty() || NOT_A_METHOD.matcher(header).find()) continue;
            Matcher h = METHOD_HEADER.matcher(header);
            if (!h.find() || STATEMENT_KEYWORDS.contains(h.group(1))) continue;
            out.add(new Method(h.group(1), i, closing(code, i, '{', '}')));
        }
        return out;
    }

    /** The module a main source belongs to: its path before {@code src/main/java}. */
    private static String moduleOf(String rel) {
        int at = rel.indexOf("src/main/java/");
        return at < 0 ? "" : rel.substring(0, at);
    }

    /** Every class, interface, enum and record body in blanked code, by name. */
    private static List<Method> types(String code) {
        List<Method> out = new ArrayList<>();
        Matcher t = TYPE_HEADER.matcher(code);
        while (t.find()) {
            int open = code.indexOf('{', t.end());
            if (open >= 0) out.add(new Method(t.group(1), open, closing(code, open, '{', '}')));
        }
        return out;
    }

    /** The innermost type declared around {@code offset}: the name a caller qualifies its member with. */
    private static String ownerAt(List<Method> types, int offset, String rel) {
        String owner = "";
        int innermost = -1;
        for (Method t : types) {
            if (t.open() < offset && offset < t.close() && t.open() > innermost) {
                owner = t.name();
                innermost = t.open();
            }
        }
        if (!owner.isEmpty()) return owner;
        String file = rel.substring(rel.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }

    /** Whether a broad handler lets a config.toml error out or shows its message, which carries the position. */
    private static boolean handlesConfigError(String handler, String var) {
        String v = Pattern.quote(var);
        return handler.contains("UserConfigParseException")
                || rethrows(handler, var)
                || Pattern.compile("Errors\\.text\\(\\s*" + v + "\\s*\\)")
                        .matcher(handler)
                        .find()
                || Pattern.compile("(?<![\\w$])" + v + "\\.getMessage\\(\\)")
                        .matcher(handler)
                        .find()
                || Pattern.compile("requestFailedLine\\([^;]*,\\s*" + v + "\\s*\\)")
                        .matcher(handler)
                        .find();
    }

    private static boolean rethrows(String handler, String var) {
        return Pattern.compile("throw\\s+" + Pattern.quote(var) + "\\s*;")
                .matcher(handler)
                .find();
    }

    private static int skipSpace(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        return i;
    }

    /** The index of the bracket that closes the one at {@code open}; the last index when unbalanced. */
    private static int closing(String code, int open, char in, char out) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == in) depth++;
            else if (ch == out && --depth == 0) return i;
        }
        return code.length() - 1;
    }
}
