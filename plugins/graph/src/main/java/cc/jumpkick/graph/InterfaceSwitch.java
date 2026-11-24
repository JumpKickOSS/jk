// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.before;
import static cc.jumpkick.graph.JavaText.importLine;
import static cc.jumpkick.graph.JavaText.past;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.graph.MoveMember.FileEdit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The uses of a class's type outside it, sorted for an extracted interface: each variable,
 * parameter, field and return type that can be declared as the interface, and every other use
 * with the reason it stays. A variable is switched when every use of its name in its file calls an
 * interface method (or {@link Object}'s) or assigns it to or from a variable of the same name; a
 * return type when every call's result is used that way or held by a switched variable. Neither is
 * switched where retyping would change an override, an overload, a method reference or a lambda's
 * target, nor a field another file reads, nor in a method a framework annotation exposes.
 */
final class InterfaceSwitch {

    private InterfaceSwitch() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** Methods every interface type has, as {@code name(params)}: a call of one needs nothing extracted. */
    static final Set<String> OBJECT = Set.of("toString()", "hashCode()", "equals(Ljava/lang/Object;)", "getClass()");

    /**
     * One use of the class's type: the file, the offsets of the type's name (its qualifier
     * included), and why it stays as the class, or {@code null} when it can be the interface.
     */
    record Place(
            FileEdit file, int start, int end, @Nullable Why reason) {

        Place(FileEdit file, int start, int end, @Nullable String reason) {
            this(file, start, end, reason == null ? null : new Why(reason, null, 0));
        }
    }

    /** Why a use stays the class, and the place in {@code file} at {@code offset} that shows it, when there is one. */
    record Why(String text, @Nullable FileEdit file, int offset) {

        /**
         * What this reason counts as when it is routine: the code names the class itself, or a
         * framework calls the method; {@code null} when the use could change.
         */
        @Nullable
        Routine routine() {
            if (file != null) return null;
            if (text.endsWith(FRAMEWORK)) return CALLED;
            return ROUTINE.get(text);
        }
    }

    /** A routine reason's label for one place and for several: {@code type argument}, {@code type arguments}. */
    record Routine(String one, String many) {

        String count(int n) {
            return n + " " + (n == 1 ? one : many);
        }
    }

    private static final String FRAMEWORK = ": a framework may call it by reflection";

    private static final Routine CALLED = new Routine("in a method a framework calls", "in methods a framework calls");

    private static final String CLASS = "names the class";

    /** The reasons that say only that the code names the class. */
    private static final Map<String, Routine> ROUTINE = Map.of(
            "a constructor call",
            new Routine("constructor call", "constructor calls"),
            "a type test",
            new Routine("type test", "type tests"),
            "a supertype",
            new Routine("supertype", "supertypes"),
            CLASS,
            new Routine("use of the class name", "uses of the class name"),
            "an array type",
            new Routine("array type", "array types"),
            "a type argument",
            new Routine("type argument", "type arguments"),
            "a cast",
            new Routine("cast", "casts"),
            "a varargs parameter",
            new Routine("varargs parameter", "varargs parameters"));

    /** A declaration typed as the class: its type's reference, where its qualifier starts, and the name it declares. */
    private record Typed(ReferenceIndex.Span type, int start, String name) {}

    /** A method of {@code file} declared as returning the class: its type and its symbol. */
    private record Returning(FileEdit file, Typed typed, String method) {}

    /**
     * Every use of {@code bin} outside {@code own}, in source order per file: those that can take
     * the interface whose methods are {@code keys} ({@code name(params)}), and the rest with a reason.
     */
    static List<Place> places(
            GraphService.Workspace w,
            CodeGraph g,
            Map<String, FileEdit> files,
            Map<String, GraphNode> types,
            String own,
            String bin,
            Set<String> keys) {
        Set<String> allowed = new TreeSet<>(OBJECT);
        allowed.addAll(keys);
        String simple =
                MoveMember.canonical(bin).substring(MoveMember.canonical(bin).lastIndexOf('.') + 1);
        Scan scan = new Scan(w, g, files, types, simple, allowed);
        List<Place> out = new ArrayList<>();
        Map<String, Set<String>> switched = new HashMap<>();
        List<Returning> returns = new ArrayList<>();
        for (String rel : RenameProof.javaFiles(g)) {
            if (rel.equals(own)) continue;
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains(simple)) continue;
            FileEdit f = MoveMember.edit(w, g, files, rel);
            if (f == null) continue;
            String code = masked(f.text);
            Map<String, List<Typed>> vars = new LinkedHashMap<>();
            Map<String, String> varReasons = new HashMap<>();
            for (ReferenceIndex.Span s : f.spans) {
                if (s.kind() != 'r' || !s.symbol().equals("T " + bin) || importLine(f.text, s.start())) continue;
                int start = qualifierStart(code, s.start());
                String other = other(code, start, s.end());
                if (other != null) {
                    out.add(new Place(f, start, s.end(), other));
                    continue;
                }
                Matcher m = NAME.matcher(code).region(pastTypeArgs(code, s.end()), code.length());
                m.lookingAt();
                int after = skip(code, m.end());
                if (after < code.length() && code.charAt(after) == '(') {
                    String method = declared(f, m.start(), "M ");
                    if (method == null) out.add(new Place(f, start, s.end(), CLASS));
                    else returns.add(new Returning(f, new Typed(s, start, m.group()), method));
                    continue;
                }
                Typed t = new Typed(s, start, m.group());
                vars.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(t);
                String why = scan.declaration(f, code, t, m.start(), m.end());
                if (why != null) varReasons.putIfAbsent(m.group(), why);
            }
            for (Map.Entry<String, List<Typed>> e : vars.entrySet()) {
                String why = varReasons.get(e.getKey());
                if (why == null) {
                    Set<Integer> names = new TreeSet<>();
                    for (Typed t : e.getValue()) {
                        names.add(pastTypeArgs(code, t.type().end()));
                    }
                    why = scan.misuse(f, code, e.getKey(), names);
                }
                if (why == null)
                    switched.computeIfAbsent(rel, k -> new TreeSet<>()).add(e.getKey());
                for (Typed t : e.getValue())
                    out.add(new Place(f, t.start(), t.type().end(), why));
            }
        }
        for (Returning r : returns) {
            Typed t = r.typed();
            Why why = scan.returned(r, switched);
            out.add(new Place(r.file(), t.start(), t.type().end(), why));
        }
        out.sort((a, b) -> a.file().rel.equals(b.file().rel)
                ? Integer.compare(a.start(), b.start())
                : a.file().rel.compareTo(b.file().rel));
        return out;
    }

    /** Whether {@code offset} in {@code code} is on its package or an import line, which names no variable. */
    private static boolean header(String code, int offset) {
        String line = JavaText.line(code, offset).stripLeading();
        return line.startsWith("package ") || line.startsWith("import ");
    }

    /** Why the use at {@code [start, end)} is no declaration it could retype, or {@code null} when it declares a name. */
    private static @Nullable String other(String code, int start, int end) {
        int b = before(code, start);
        String word = b < 0 ? "" : wordEnding(code, b);
        if (word.equals("new")) return "a constructor call";
        if (word.equals("instanceof") || word.equals("case")) return "a type test";
        if (word.equals("extends") || word.equals("implements")) return "a supertype";
        int k = pastTypeArgs(code, end);
        if (k >= code.length()) return CLASS;
        char c = code.charAt(k);
        if (c == '.' || c == ':') return CLASS;
        if (c == '[') return "an array type";
        if (c == '>' || c == ',' && b >= 0 && (code.charAt(b) == '<' || code.charAt(b) == ','))
            return "a type argument";
        if (c == ')' && b >= 0 && code.charAt(b) == '(') return "a cast";
        if (code.startsWith("...", k)) return "a varargs parameter";
        Matcher m = NAME.matcher(code).region(k, code.length());
        return m.lookingAt() ? null : CLASS;
    }

    /** The searches one sort shares: the files that name a member, read once each. */
    private static final class Scan {
        final GraphService.Workspace w;
        final CodeGraph g;
        final Map<String, FileEdit> files;
        final Map<String, GraphNode> types;
        final String simple;
        final Set<String> keys;
        final Map<String, List<FileEdit>> naming = new HashMap<>();

        Scan(
                GraphService.Workspace w,
                CodeGraph g,
                Map<String, FileEdit> files,
                Map<String, GraphNode> types,
                String simple,
                Set<String> keys) {
            this.w = w;
            this.g = g;
            this.files = files;
            this.types = types;
            this.simple = simple;
            this.keys = keys;
        }

        /** Every indexed workspace Java file that holds {@code name} as a word. */
        List<FileEdit> naming(String name) {
            return naming.computeIfAbsent(name, n -> {
                Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(n) + "(?![\\w$])");
                List<FileEdit> out = new ArrayList<>();
                for (String rel : RenameProof.javaFiles(g)) {
                    String text = RenameText.read(w.root(), rel);
                    if (text == null || !word.matcher(text).find()) continue;
                    FileEdit f = MoveMember.edit(w, g, files, rel);
                    if (f != null) out.add(f);
                }
                return out;
            });
        }

        /**
         * Why the variable {@code t} declares, named at {@code [name, end)}, cannot change its type
         * whatever its uses: {@code null} for a local, a field no other file reads, or a parameter of
         * a method whose signature is its own.
         */
        @Nullable
        String declaration(FileEdit f, String code, Typed t, int name, int end) {
            int open = enclosingParen(code, t.start());
            if (open < 0) {
                int k = skip(code, end);
                if (k < code.length() && code.charAt(k) == ',') return "declares more than one variable";
                if (k < code.length() && code.charAt(k) == '=' && moreDeclarators(code, k + 1)) {
                    return "declares more than one variable";
                }
                String field = declared(f, name, "F ");
                return field == null || JavaText.modifiers(f.text, name).contains("private")
                        ? null
                        : readElsewhere(f, field, t.name());
            }
            int close = closing(code, open);
            if (close >= 0 && code.startsWith("->", skip(code, close + 1))) {
                return "a lambda parameter: its target type fixes it";
            }
            int b = before(code, open);
            String word = b < 0 ? "" : wordEnding(code, b);
            if (word.equals("for") || word.equals("try")) return null;
            if (word.equals("catch")) return "a catch parameter";
            int at = b - word.length() + 1;
            String method = word.isEmpty() ? null : declared(f, at, "M ");
            if (method != null) return signature(f, method, at);
            String type = word.isEmpty() ? null : declared(f, at, "T ");
            if (type == null) return CLASS;
            if (wordEnding(code, before(code, at)).equals("record")) return "a record component";
            // A constructor's name is recorded as its class.
            int arity = arity(code, open);
            for (ReferenceIndex.Span s : f.spans) {
                if (s.kind() != 'd' || !s.symbol().equals(type) || s.start() == at) continue;
                int paren = skip(code, s.end());
                if (paren < code.length() && code.charAt(paren) == '(' && arity(code, paren) == arity) {
                    return "the constructor has an overload of as many parameters";
                }
            }
            return null;
        }

        /**
         * Why method {@code symbol}, named at {@code at} in {@code f}, cannot change its signature: it
         * overrides, is abstract (a lambda or an implementation fixes it), has an overload or another
         * type's method of its name and parameters, or a method reference names it.
         */
        @Nullable
        String signature(FileEdit f, String symbol, int at) {
            String owner = symbol.substring(2, symbol.indexOf('#'));
            String sig = symbol.substring(symbol.indexOf('#') + 1);
            String name = sig.substring(0, sig.indexOf('('));
            String params = sig.substring(sig.indexOf('('), sig.indexOf(')') + 1);
            String shown = name.equals("<init>") ? "the constructor" : name + "()";
            int from = Math.max(
                    Math.max(f.text.lastIndexOf(';', at), f.text.lastIndexOf('{', at)), f.text.lastIndexOf('}', at));
            String head = f.text.substring(from + 1, at);
            if (head.contains("@Override")) return shown + " overrides a method";
            for (String a : SafeDelete.annotations(List.of(), List.of(head.split("\n")))) {
                if (!SafeDelete.inert(a)) return "@" + a + " on " + shown + FRAMEWORK;
            }
            GraphNode type = types.get(owner);
            String kind = type == null ? "" : String.valueOf(Decl.kind(type));
            int body = ExtractInterface.bodyStart(f.text, at);
            if (kind.equals("interface")
                    || kind.equals("annotation")
                    || body < f.text.length() && f.text.charAt(body) == ';') {
                return shown + " is abstract: a lambda or an implementation fixes its types";
            }
            int arity = arity(params);
            String lookup = name.equals("<init>") ? ReferenceIndex.simpleName("T " + owner) : name;
            for (FileEdit o : naming(lookup)) {
                for (ReferenceIndex.Span s : o.spans) {
                    if (!s.symbol().startsWith("M ") || s.symbol().equals(symbol)) continue;
                    String other = s.symbol().substring(s.symbol().indexOf('#') + 1);
                    if (!other.startsWith(name + "(")) continue;
                    String otherOwner = s.symbol().substring(2, s.symbol().indexOf('#'));
                    String otherParams = other.substring(other.indexOf('('), other.indexOf(')') + 1);
                    if (s.kind() == 'd' && otherOwner.equals(owner) && arity(otherParams) == arity) {
                        return shown + " has an overload of as many parameters";
                    }
                    if (s.kind() == 'd' && !otherOwner.equals(owner) && otherParams.equals(params)) {
                        return shown + " may be overridden in " + o.rel;
                    }
                }
                if (!name.equals("<init>")
                        && Pattern.compile("::\\s*" + Pattern.quote(name) + "(?![\\w$])")
                                .matcher(masked(o.text))
                                .find()) {
                    return "a method reference to " + shown + " in " + o.rel + " fixes its types";
                }
            }
            return null;
        }

        /** Why field {@code symbol} stays: another file reads or writes it. */
        @Nullable
        String readElsewhere(FileEdit f, String symbol, String name) {
            for (FileEdit o : naming(name)) {
                if (o.rel.equals(f.rel)) continue;
                for (ReferenceIndex.Span s : o.spans) {
                    if ((s.kind() == 'r' || s.kind() == 'w') && s.symbol().equals(symbol)) {
                        return "a field " + o.rel + " uses";
                    }
                }
            }
            return null;
        }

        /**
         * Why variable {@code name} in {@code f} must stay the class: the first use, besides its
         * declarations at {@code declared}, that is not a call of an interface method or an assignment
         * to or from a variable of the same name; {@code null} when there is none.
         */
        @Nullable
        String misuse(FileEdit f, String code, String name, Set<Integer> declared) {
            Matcher m = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])")
                    .matcher(code);
            while (m.find()) {
                if (declared.contains(m.start()) || header(code, m.start())) continue;
                int b = before(code, m.start());
                if (b >= 0
                        && code.charAt(b) == '.'
                        && !wordEnding(code, before(code, b)).equals("this")) {
                    return "a member of another object shares its name";
                }
                int k = skip(code, m.end());
                if (k < code.length()
                        && code.charAt(k) == '='
                        && (k + 1 >= code.length() || code.charAt(k + 1) != '=')) {
                    continue;
                }
                if ((code.startsWith("==", k) || code.startsWith("!=", k))
                        && wordAt(code, skip(code, k + 2)).equals("null")) {
                    continue;
                }
                if (b >= 0 && code.charAt(b) == '.') b = before(code, before(code, b) - "this".length() + 1);
                if (b >= 0 && code.charAt(b) == '=' && (b == 0 || "=!<>".indexOf(code.charAt(b - 1)) < 0)) {
                    String lhs = wordEnding(code, before(code, b));
                    if (lhs.equals(name)) continue;
                    return "assigned to " + (lhs.isEmpty() ? "another variable" : lhs);
                }
                if (k < code.length() && code.charAt(k) == '.') {
                    int c = skip(code, k + 1);
                    ReferenceIndex.Span called = null;
                    for (ReferenceIndex.Span s : f.spans) {
                        if (s.start() == c
                                && (s.symbol().startsWith("M ") || s.symbol().startsWith("F "))) {
                            called = s;
                            break;
                        }
                    }
                    if (called != null && called.symbol().startsWith("M ")) {
                        String sig = called.symbol().substring(called.symbol().indexOf('#') + 1);
                        if (keys.contains(sig.substring(0, sig.indexOf(')') + 1))) continue;
                        return "calls " + sig.substring(0, sig.indexOf('(')) + "()";
                    }
                    if (called != null) return "reads its field " + ReferenceIndex.simpleName(called.symbol());
                    return "uses it as " + simple;
                }
                if (wordEnding(code, b).equals("return")) return "returned";
                if (b >= 0 && (code.charAt(b) == '(' || code.charAt(b) == ',')
                        || k < code.length() && (code.charAt(k) == ')' || code.charAt(k) == ',')) {
                    return "passed on";
                }
                return "uses it as " + simple;
            }
            return null;
        }

        /**
         * Why method {@code r} must keep returning the class: its signature is fixed (see {@link
         * #signature}), or a call's result is used other than through an interface method or a
         * variable switched in {@code switched} (by file).
         */
        @Nullable
        Why returned(Returning r, Map<String, Set<String>> switched) {
            FileEdit f = r.file();
            Typed t = r.typed();
            String symbol = r.method();
            int at = skip(masked(f.text), t.type().end());
            String why = signature(f, symbol, at);
            if (why != null) return new Why(why, null, 0);
            for (FileEdit o : naming(t.name())) {
                String code = masked(o.text);
                for (ReferenceIndex.Span s : o.spans) {
                    if (s.kind() != 'r' || !s.symbol().equals(symbol)) continue;
                    int open = skip(code, s.end());
                    if (open >= code.length() || code.charAt(open) != '(') {
                        return new Why("a reference fixes its type", o, s.start());
                    }
                    int close = closing(code, open);
                    int k = close < 0 ? code.length() : skip(code, close + 1);
                    if (k < code.length() && code.charAt(k) == '.') {
                        int c = skip(code, k + 1);
                        boolean ok = false;
                        for (ReferenceIndex.Span x : o.spans) {
                            if (x.start() == c && x.symbol().startsWith("M ")) {
                                String sig = x.symbol().substring(x.symbol().indexOf('#') + 1);
                                ok = keys.contains(sig.substring(0, sig.indexOf(')') + 1));
                                break;
                            }
                        }
                        if (ok) continue;
                    } else {
                        int b = before(code, expressionStart(code, s.start()));
                        // A call made for its effect alone: nothing holds the result.
                        if (k < code.length()
                                && code.charAt(k) == ';'
                                && (b < 0 || "{};".indexOf(code.charAt(b)) >= 0)) {
                            continue;
                        }
                        if (b >= 0 && code.charAt(b) == '=' && (b == 0 || "=!<>".indexOf(code.charAt(b - 1)) < 0)) {
                            String lhs = wordEnding(code, before(code, b));
                            if (switched.getOrDefault(o.rel, Set.of()).contains(lhs)) continue;
                        }
                    }
                    return new Why("its result is used as " + simple, o, s.start());
                }
            }
            return null;
        }
    }

    /** The symbol {@code f}'s index declares at {@code offset} starting {@code prefix}, or {@code null}. */
    private static @Nullable String declared(FileEdit f, int offset, String prefix) {
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() == 'd' && s.start() == offset && s.symbol().startsWith(prefix)) return s.symbol();
        }
        return null;
    }

    /** The start of the qualified name whose last part starts at {@code at}: {@code a.b.Book} from {@code Book}. */
    private static int qualifierStart(String code, int at) {
        int start = at;
        while (true) {
            int b = before(code, start);
            if (b < 0 || code.charAt(b) != '.') return start;
            String word = wordEnding(code, before(code, b));
            if (word.isEmpty() || !Character.isJavaIdentifierStart(word.charAt(0))) return start;
            start = before(code, b) - word.length() + 1;
        }
    }

    /** The start of the call chain ending in the name at {@code at}: {@code a.b().c} from {@code c}. */
    private static int expressionStart(String code, int at) {
        int start = at;
        while (true) {
            int b = before(code, start);
            if (b < 0 || code.charAt(b) != '.') return start;
            int k = before(code, b);
            if (k >= 0 && code.charAt(k) == ')') k = before(code, opening(code, k));
            String word = k < 0 ? "" : wordEnding(code, k);
            if (word.isEmpty()) return start;
            start = k - word.length() + 1;
        }
    }

    /** The identifier ending at {@code end} inclusive, or empty. */
    private static String wordEnding(String code, int end) {
        if (end < 0) return "";
        int i = end;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) i--;
        return code.substring(i + 1, end + 1);
    }

    /** The identifier starting at {@code start}, or empty. */
    private static String wordAt(String code, int start) {
        Matcher m = NAME.matcher(code).region(Math.min(start, code.length()), code.length());
        return m.lookingAt() ? m.group() : "";
    }

    /** The first offset at or after {@code i} that is not whitespace. */
    private static int skip(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        return i;
    }

    /** The offset of the first non-blank past {@code i} and any type arguments there: the name after {@code Box<String> }. */
    private static int pastTypeArgs(String code, int i) {
        int k = skip(code, i);
        if (k >= code.length() || code.charAt(k) != '<') return k;
        int depth = 0;
        for (int j = k; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) return skip(code, j + 1);
            else if (c == ';' || c == '{' || c == '}' || c == '(' || c == ')') return k;
        }
        return k;
    }

    /**
     * The {@code (} that encloses {@code at} within its statement (a parameter list, a {@code for},
     * a lambda's parameters), or -1 when a {@code ;}, {@code {} or {@code }} comes first.
     */
    private static int enclosingParen(String code, int at) {
        int depth = 0;
        for (int k = at - 1; k >= 0; k--) {
            char c = code.charAt(k);
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') {
                if (depth == 0) return c == '(' ? k : -1;
                depth--;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) return -1;
        }
        return -1;
    }

    /** Whether the initializer starting at {@code from} is followed by another declarator before its {@code ;}. */
    private static boolean moreDeclarators(String code, int from) {
        int depth = 0;
        for (int k = from; k < code.length(); k++) {
            char c = code.charAt(k);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) return false;
            } else if (c == '<' && k > 0 && Character.isJavaIdentifierPart(code.charAt(k - 1))) depth++;
            else if (c == '>' && k > 0 && code.charAt(k - 1) != '-' && code.charAt(k - 1) != ' ' && depth > 0) depth--;
            else if (depth == 0 && c == ';') return false;
            else if (depth == 0 && c == ',') return true;
        }
        return false;
    }

    /** The offset of the {@code )} closing the {@code (} at {@code open}, or -1. */
    private static int closing(String code, int open) {
        int depth = 0;
        for (int k = open; k < code.length(); k++) {
            char c = code.charAt(k);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return k;
        }
        return -1;
    }

    /** The offset of the {@code (} opening the {@code )} at {@code close}, or 0. */
    private static int opening(String code, int close) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            char c = code.charAt(k);
            if (c == ')') depth++;
            else if (c == '(' && --depth == 0) return k;
        }
        return 0;
    }

    /** The number of parameters the list opening at {@code open} declares. */
    private static int arity(String code, int open) {
        int close = closing(code, open);
        if (close < 0 || code.substring(open + 1, close).isBlank()) return 0;
        int n = 1;
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            char c = code.charAt(k);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) n++;
        }
        return n;
    }

    /** The number of parameters a descriptor's {@code (...)} holds. */
    private static int arity(String params) {
        int n = 0;
        for (int i = 1; i < params.length() - 1; i++) {
            char c = params.charAt(i);
            if (c == '[') continue;
            if (c == 'L') i = params.indexOf(';', i);
            n++;
        }
        return n;
    }

    /** {@code text} with its comments and literals blanked, newlines kept, so offsets still match. */
    static String masked(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            int p = past(text, i);
            if (p == i) {
                i++;
                continue;
            }
            for (int k = i; k < p; k++) {
                if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
            }
            i = p;
        }
        return out.toString();
    }
}
