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
 * carries {@code instead}. One row per javac or kotlinc error whose repair is mechanical; the row
 * names the compiler's own code for it and recognises it by the shape of the message, because text
 * is all the compilers hand jk: Zinc's reporter carries no {@code compiler.err.*} key, javac's
 * stderr never had one, and kotlinc's Build Tools logger reports a line of text. The hint quotes
 * the symbol, package or type from the message itself and never guesses a coordinate.
 */
@NullMarked
final class JkResultsHints {

    /** {@code code} is the compiler's name for the diagnostic; {@code text} the one-line repair. */
    record Hint(String code, String text) {}

    static final String ADD = "`jk add` the dependency that provides it";

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
            case "javac" -> javac(first, message);
            case "kotlinc" -> kotlinc(first);
            default -> null;
        };
    }

    private static @Nullable Hint javac(String first, String message) {
        if (first.startsWith("cannot find symbol")) {
            String symbol = field(message, "symbol:");
            String location = field(message, "location:");
            String what = symbol.isEmpty() ? "the name" : "`" + symbol + "`";
            String where = location.isEmpty() ? "here" : "in `" + location + "`";
            return new Hint(
                    "compiler.err.cant.resolve.location",
                    what + " is not declared " + where + " and not imported: fix the name, add the import, or " + ADD
                            + ".");
        }
        Matcher m = PACKAGE.matcher(first);
        if (m.find()) {
            return new Hint(
                    "compiler.err.doesnt.exist",
                    "nothing on this module's compile classpath provides package `" + m.group(1)
                            + "`: `jk add <group:artifact>` the library that ships it, or fix the import.");
        }
        m = CONVERT.matcher(first);
        if (m.find()) {
            return new Hint(
                    "compiler.err.prob.found.req",
                    "the value is `" + m.group(1) + "` where `" + m.group(2)
                            + "` is required: change the declared type, convert the value, or cast when the narrowing is intended.");
        }
        m = UNREPORTED.matcher(first);
        if (m.find()) {
            String ex = m.group(1);
            return new Hint(
                    "compiler.err.unreported.exception.need.to.catch.or.throw",
                    "catch `" + ex + "` around the call, or add `throws " + ex + "` to the enclosing method.");
        }
        if (first.startsWith("missing return statement")) {
            return new Hint(
                    "compiler.err.missing.ret.stmt",
                    "every path out of the method must return a value: add a return after the last branch.");
        }
        m = UNINITIALIZED.matcher(first);
        if (m.find()) {
            return new Hint(
                    "compiler.err.var.might.not.have.been.initialized",
                    "`" + m.group(1)
                            + "` is read on a path that never assigned it: initialize it at the declaration or on every branch.");
        }
        m = STATIC_CONTEXT.matcher(first);
        if (m.find()) {
            return new Hint(
                    "compiler.err.non-static.cant.be.ref",
                    "`" + m.group(1)
                            + "` belongs to an instance: call it on one, or make it static when it uses no instance state.");
        }
        return null;
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
