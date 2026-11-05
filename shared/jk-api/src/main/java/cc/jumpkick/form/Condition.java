// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A field's {@code when}: the text as written, and the expression it parses to.
 *
 * <pre>
 * expr    = or
 * or      = and ( "||" and )*
 * and     = unary ( "&amp;&amp;" unary )*
 * unary   = "!" unary | "(" expr ")" | test
 * test    = key ( "==" | "!=" ) literal | key "in" "[" literal ( "," literal )* "]" | key
 * literal = 'text' | "text" | true | false
 * </pre>
 *
 * <p>A bare {@code key} tests a {@code bool} field. A key names an earlier field only, and a literal
 * must suit that field's kind: a boolean for {@code bool}, one of the options for {@code choice} and
 * {@code multi}, a string otherwise. {@code multi} is tested with {@code in}, which holds when the
 * selection shares an option with the list. A key with no value compares unequal to every literal.
 */
public record Condition(String source, Expr expr) {

    /** A parsed condition. Literals are a {@code String} or a {@code Boolean}. */
    public sealed interface Expr {}

    /** {@code key == literal}, or {@code key != literal} when {@code negated}. */
    public record Compare(String key, Object literal, boolean negated) implements Expr {}

    /** {@code key in [literals]}. */
    public record In(String key, List<Object> literals) implements Expr {
        public In {
            literals = List.copyOf(literals);
        }
    }

    /** A bare {@code bool} key. */
    public record Flag(String key) implements Expr {}

    public record Not(Expr operand) implements Expr {}

    public record And(Expr left, Expr right) implements Expr {}

    public record Or(Expr left, Expr right) implements Expr {}

    /** Parses {@code source}; a syntax error is a {@link FormException} naming the column. */
    public static Condition parse(String source) {
        return new Condition(source, new ConditionParser(source).parse());
    }

    /** Every key the condition names, in the order written. */
    public Set<String> keys() {
        Set<String> out = new LinkedHashSet<>();
        collect(expr, out);
        return out;
    }

    /** Evaluates against the values settled so far; an absent key has no value. */
    public boolean test(Map<String, ?> values) {
        return eval(expr, values);
    }

    /**
     * Checks every key against {@code earlier}, the fields before the one this condition belongs to,
     * and {@code all}, every field of the form, so a later key is told apart from an unknown one.
     */
    void check(String owner, Map<String, Field> earlier, Set<String> all) {
        for (String key : keys()) {
            if (key.equals(owner)) {
                throw fault(owner, "names the field itself");
            }
            if (!earlier.containsKey(key)) {
                throw fault(
                        owner,
                        all.contains(key)
                                ? "names '" + key + "', which comes after it; a condition names earlier fields only"
                                : "names '" + key + "', which is not a field of this form");
            }
        }
        typeCheck(owner, expr, earlier);
    }

    private FormException fault(String owner, String message) {
        return new FormException("when of '" + owner + "' (" + source + ") " + message);
    }

    private void typeCheck(String owner, Expr e, Map<String, Field> fields) {
        switch (e) {
            case Compare c -> {
                Field f = field(fields, c.key());
                if (f.kind() == FieldKind.MULTI) {
                    throw fault(owner, "compares multi field '" + c.key() + "'; test it with in [...]");
                }
                literalFits(owner, f, c.literal());
            }
            case In in -> {
                Field f = field(fields, in.key());
                if (f.kind() == FieldKind.BOOL) {
                    throw fault(
                            owner,
                            "tests bool field '" + in.key() + "' with in; write " + in.key() + " or !" + in.key());
                }
                for (Object literal : in.literals()) literalFits(owner, f, literal);
            }
            case Flag flag -> {
                Field f = field(fields, flag.key());
                if (f.kind() != FieldKind.BOOL) {
                    throw fault(
                            owner,
                            "uses " + f.kind().id() + " field '" + flag.key() + "' as a boolean; compare it: "
                                    + flag.key() + " == '…'");
                }
            }
            case Not not -> typeCheck(owner, not.operand(), fields);
            case And and -> {
                typeCheck(owner, and.left(), fields);
                typeCheck(owner, and.right(), fields);
            }
            case Or or -> {
                typeCheck(owner, or.left(), fields);
                typeCheck(owner, or.right(), fields);
            }
        }
    }

    private static Field field(Map<String, Field> fields, String key) {
        return Objects.requireNonNull(fields.get(key), key);
    }

    private void literalFits(String owner, Field f, Object literal) {
        if (f.kind() == FieldKind.BOOL) {
            if (!(literal instanceof Boolean)) {
                throw fault(owner, "compares bool field '" + f.key() + "' with '" + literal + "'; use true or false");
            }
            return;
        }
        if (!(literal instanceof String text)) {
            throw fault(
                    owner,
                    "compares " + f.kind().id() + " field '" + f.key() + "' with " + literal + "; use a quoted string");
        }
        if (!f.options().isEmpty() && f.option(text) == null) {
            throw fault(
                    owner,
                    "'" + text + "' is not an option of '" + f.key() + "' ("
                            + f.options().stream().map(Option::id).collect(Collectors.joining(", ")) + ")");
        }
    }

    private static void collect(Expr e, Set<String> out) {
        switch (e) {
            case Compare c -> out.add(c.key());
            case In in -> out.add(in.key());
            case Flag f -> out.add(f.key());
            case Not n -> collect(n.operand(), out);
            case And a -> {
                collect(a.left(), out);
                collect(a.right(), out);
            }
            case Or o -> {
                collect(o.left(), out);
                collect(o.right(), out);
            }
        }
    }

    private static boolean eval(Expr e, Map<String, ?> values) {
        return switch (e) {
            case Compare c -> {
                boolean equal = c.literal().equals(values.get(c.key()));
                yield c.negated() != equal;
            }
            case In in -> {
                @Nullable Object value = values.get(in.key());
                if (value instanceof List<?> selected) {
                    yield selected.stream().anyMatch(in.literals()::contains);
                }
                yield value != null && in.literals().contains(value);
            }
            case Flag f -> Boolean.TRUE.equals(values.get(f.key()));
            case Not n -> !eval(n.operand(), values);
            case And a -> eval(a.left(), values) && eval(a.right(), values);
            case Or o -> eval(o.left(), values) || eval(o.right(), values);
        };
    }
}
