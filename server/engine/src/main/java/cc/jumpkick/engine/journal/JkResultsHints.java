// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.diagnostic.CompilerLocus;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The repair hint under a compiler diagnostic in {@code jk-results.md}, the way a guard violation
 * carries {@code instead}. One row per javac or kotlinc error whose repair is mechanical. A javac
 * row is chosen by the diagnostic's own key ({@code compiler.err.cant.resolve.location}), which the
 * compile worker records beside every diagnostic; the shape of the message is the fallback for a
 * diagnostic that arrived without one — a forked javac's stderr, and every kotlinc diagnostic, since
 * kotlinc's Build Tools logger reports a line of text. The hint quotes the symbol, package or type
 * from the message itself and never guesses a coordinate.
 */
@NullMarked
final class JkResultsHints {

    /** {@code code} is the compiler's name for the diagnostic; {@code text} the one-line repair. */
    record Hint(String code, String text) {}

    static final String ADD = "`jk add` the dependency that provides it";

    static final String CANT_RESOLVE = "compiler.err.cant.resolve.location";
    static final String DOESNT_EXIST = "compiler.err.doesnt.exist";
    static final String PROB_FOUND_REQ = "compiler.err.prob.found.req";
    static final String UNREPORTED_EXCEPTION = "compiler.err.unreported.exception.need.to.catch.or.throw";
    static final String MISSING_RETURN = "compiler.err.missing.ret.stmt";
    static final String UNINITIALIZED_VAR = "compiler.err.var.might.not.have.been.initialized";
    static final String NON_STATIC = "compiler.err.non-static.cant.be.ref";

    private static final Pattern PACKAGE = Pattern.compile("^package (\\S+) does not exist");
    private static final Pattern CONVERT = Pattern.compile("^incompatible types: (.+?) cannot be converted to (.+)$");
    private static final Pattern UNREPORTED = Pattern.compile("^unreported exception (\\S+?);");
    private static final Pattern UNINITIALIZED = Pattern.compile("^variable (\\S+) might not have been initialized");
    private static final Pattern STATIC_CONTEXT =
            Pattern.compile("^non-static (?:method|variable) (.+?) cannot be referenced from a static context");
    private static final Pattern KT_UNRESOLVED = Pattern.compile("(?i)unresolved reference[: ]+'?([^'.\\s]+)'?");
    private static final Pattern KT_NO_VALUE = Pattern.compile("(?i)no value passed for parameter '?([^'.\\s]+)'?");

    private JkResultsHints() {}

    /** The hint for {@code d}, or {@code null}: only javac and kotlinc errors have one. */
    static @Nullable Hint forDiag(BuildRecord.Diag d) {
        if (!JkResultsMarkdown.isError(d)) return null;
        String message = d.message() == null ? "" : d.message();
        String first = firstLine(message);
        return switch (d.code()) {
            case "javac" -> {
                Hint byKey = javacByKey(d.key(), first, message);
                yield byKey != null ? byKey : javacByShape(first, message);
            }
            case "kotlinc" -> kotlinc(first);
            default -> null;
        };
    }

    /**
     * The row javac's own key selects, or {@code null} when the key is empty or has no row. The
     * text still quotes the message's symbol, package or types when the message carries them.
     */
    private static @Nullable Hint javacByKey(String key, String first, String message) {
        return switch (key) {
            case CANT_RESOLVE,
                    "compiler.err.cant.resolve",
                    "compiler.err.cant.resolve.args",
                    "compiler.err.cant.resolve.location.args" -> cantResolve(key, message);
            case DOESNT_EXIST -> doesntExist(group(PACKAGE, first, "the imported package"), message);
            case PROB_FOUND_REQ -> {
                Matcher m = CONVERT.matcher(first);
                yield m.find()
                        ? probFoundReq(m.group(1), m.group(2))
                        : new Hint(
                                PROB_FOUND_REQ,
                                "the value's type is not the one the declaration requires: change the declared type, convert the value, or cast when the narrowing is intended.");
            }
            case UNREPORTED_EXCEPTION -> unreported(group(UNREPORTED, first, "the checked exception"));
            case MISSING_RETURN -> missingReturn();
            case UNINITIALIZED_VAR -> uninitialized(group(UNINITIALIZED, first, "the variable"));
            case NON_STATIC -> nonStatic(group(STATIC_CONTEXT, first, "the member"));
            default -> null;
        };
    }

    /** The row the message's shape selects, for a javac diagnostic that arrived without a key. */
    private static @Nullable Hint javacByShape(String first, String message) {
        if (first.startsWith("cannot find symbol")) return cantResolve(CANT_RESOLVE, message);
        Matcher m = PACKAGE.matcher(first);
        if (m.find()) return doesntExist(m.group(1), message);
        m = CONVERT.matcher(first);
        if (m.find()) return probFoundReq(m.group(1), m.group(2));
        m = UNREPORTED.matcher(first);
        if (m.find()) return unreported(m.group(1));
        if (first.startsWith("missing return statement")) return missingReturn();
        m = UNINITIALIZED.matcher(first);
        if (m.find()) return uninitialized(m.group(1));
        m = STATIC_CONTEXT.matcher(first);
        if (m.find()) return nonStatic(m.group(1));
        return null;
    }

    private static Hint cantResolve(String key, String message) {
        String symbol = field(message, "symbol:");
        String location = field(message, "location:");
        String what = symbol.isEmpty() ? "the name" : "`" + symbol + "`";
        String where = location.isEmpty() ? "here" : "in `" + location + "`";
        return new Hint(
                key,
                what + " is not declared " + where + " and not imported: fix the name, add the import, or " + ADD
                        + ".");
    }

    /**
     * The compile step writes {@code provided by: g:a (where)} under the error when the lock or the
     * catalog knows the package; the hint then names that coordinate for {@code jk add}.
     */
    private static Hint doesntExist(String pkg, String message) {
        String provider = field(message, "provided by:");
        if (provider.isEmpty()) {
            return new Hint(
                    DOESNT_EXIST,
                    "nothing on this module's compile classpath provides package `" + pkg
                            + "`: `jk add <group:artifact>` the library that ships it, or fix the import.");
        }
        int space = provider.indexOf(' ');
        String coordinate = space < 0 ? provider : provider.substring(0, space);
        String where = space < 0 ? "" : " (" + provider.substring(space + 1).replaceAll("^\\(|\\)$", "") + ")";
        return new Hint(
                DOESNT_EXIST,
                "package `" + pkg + "` is provided by `" + coordinate + "`" + where + ": `jk add " + coordinate
                        + "` in this module, or fix the import.");
    }

    private static Hint probFoundReq(String found, String required) {
        return new Hint(
                PROB_FOUND_REQ,
                "the value is `" + found + "` where `" + required
                        + "` is required: change the declared type, convert the value, or cast when the narrowing is intended.");
    }

    private static Hint unreported(String exception) {
        return new Hint(
                UNREPORTED_EXCEPTION,
                "catch `" + exception + "` around the call, or add `throws " + exception
                        + "` to the enclosing method.");
    }

    private static Hint missingReturn() {
        return new Hint(
                MISSING_RETURN,
                "every path out of the method must return a value: add a return after the last branch.");
    }

    private static Hint uninitialized(String variable) {
        return new Hint(
                UNINITIALIZED_VAR,
                "`" + variable
                        + "` is read on a path that never assigned it: initialize it at the declaration or on every branch.");
    }

    private static Hint nonStatic(String member) {
        return new Hint(
                NON_STATIC,
                "`" + member
                        + "` belongs to an instance: call it on one, or make it static when it uses no instance state.");
    }

    /** The pattern's first group in {@code text}, or {@code fallback} when the message has another shape. */
    private static String group(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private static @Nullable Hint kotlinc(String first) {
        String lower = first.toLowerCase(Locale.ROOT);
        Matcher m = KT_UNRESOLVED.matcher(first);
        if (m.find()) {
            return new Hint(
                    "UNRESOLVED_REFERENCE",
                    "`" + m.group(1)
                            + "` is not declared, imported or on this module's compile classpath: fix the name, add the import, or "
                            + ADD + ".");
        }
        if (lower.contains("type mismatch")) {
            return new Hint(
                    "TYPE_MISMATCH",
                    "the value's type is not the one the declaration wants: change the declared type, convert the value, or cast with `as` when the narrowing is intended.");
        }
        if (lower.contains("only safe (?.) or non-null asserted (!!.) calls are allowed")) {
            return new Hint(
                    "UNSAFE_CALL",
                    "the receiver may be null: call through `?.`, check for null first, or make the type non-null where the value is produced.");
        }
        m = KT_NO_VALUE.matcher(first);
        if (m.find()) {
            return new Hint(
                    "NO_VALUE_FOR_PARAMETER",
                    "pass an argument for `" + m.group(1) + "`, or give the parameter a default value.");
        }
        return null;
    }

    /**
     * The diagnostic's first line without its {@code path:line:col:} header and severity label: the
     * text javac or kotlinc wrote, so the rows above match a bare message and a headed one alike.
     */
    static String firstLine(String message) {
        int nl = message.indexOf('\n');
        String line = (nl < 0 ? message : message.substring(0, nl)).strip();
        Matcher h = CompilerLocus.HEADER.matcher(line);
        if (h.matches()) line = h.group("rest").strip();
        for (String label : new String[] {"error:", "e:", "warning:", "w:"}) {
            if (line.regionMatches(true, 0, label, 0, label.length())) {
                line = line.substring(label.length()).strip();
                break;
            }
        }
        return line;
    }

    /** The value of javac's indented {@code symbol:} / {@code location:} line, or {@code ""}. */
    private static String field(String message, String label) {
        for (String line : message.split("\n")) {
            String s = line.strip();
            if (s.startsWith(label)) return s.substring(label.length()).strip();
        }
        return "";
    }
}
