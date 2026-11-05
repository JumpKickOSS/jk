// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import cc.jumpkick.form.Condition.And;
import cc.jumpkick.form.Condition.Compare;
import cc.jumpkick.form.Condition.Expr;
import cc.jumpkick.form.Condition.Flag;
import cc.jumpkick.form.Condition.In;
import cc.jumpkick.form.Condition.Not;
import cc.jumpkick.form.Condition.Or;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Recursive descent over the grammar on {@link Condition}; one instance parses one source. */
final class ConditionParser {

    private enum Kind {
        KEY,
        STRING,
        TRUE,
        FALSE,
        EQ,
        NE,
        IN,
        AND,
        OR,
        NOT,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        COMMA,
        END
    }

    /** One token; {@code column} is 1-based, for messages. */
    private record Token(Kind kind, String text, int column) {}

    private final String source;
    private final List<Token> tokens;
    private int at;

    ConditionParser(String source) {
        this.source = source;
        this.tokens = tokenize(source);
    }

    Expr parse() {
        if (peek().kind() == Kind.END) throw fail(peek(), "is empty");
        Expr e = or();
        if (peek().kind() != Kind.END) throw fail(peek(), "has '" + peek().text() + "' where it should end");
        return e;
    }

    private Expr or() {
        Expr left = and();
        while (accept(Kind.OR)) left = new Or(left, and());
        return left;
    }

    private Expr and() {
        Expr left = unary();
        while (accept(Kind.AND)) left = new And(left, unary());
        return left;
    }

    private Expr unary() {
        if (accept(Kind.NOT)) return new Not(unary());
        if (accept(Kind.LPAREN)) {
            Expr inner = or();
            expect(Kind.RPAREN, "')'");
            return inner;
        }
        Token key = peek();
        if (key.kind() != Kind.KEY) throw fail(key, "expects a field key, got " + describe(key));
        at++;
        if (accept(Kind.EQ)) return new Compare(key.text(), literal(), false);
        if (accept(Kind.NE)) return new Compare(key.text(), literal(), true);
        if (accept(Kind.IN)) {
            expect(Kind.LBRACKET, "'['");
            List<Object> literals = new ArrayList<>();
            literals.add(literal());
            while (accept(Kind.COMMA)) literals.add(literal());
            expect(Kind.RBRACKET, "']'");
            return new In(key.text(), literals);
        }
        return new Flag(key.text());
    }

    private Object literal() {
        Token t = peek();
        at++;
        return switch (t.kind()) {
            case STRING -> t.text();
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            default -> throw fail(t, "expects a quoted string, true or false, got " + describe(t));
        };
    }

    private boolean accept(Kind kind) {
        if (peek().kind() != kind) return false;
        at++;
        return true;
    }

    private void expect(Kind kind, String what) {
        if (!accept(kind)) throw fail(peek(), "expects " + what + ", got " + describe(peek()));
    }

    private Token peek() {
        return tokens.get(at);
    }

    private static String describe(Token t) {
        return t.kind() == Kind.END ? "the end" : "'" + t.text() + "'";
    }

    private FormException fail(Token t, String message) {
        return new FormException("condition \"" + source + "\" " + message + " (column " + t.column() + ")");
    }

    private List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int column = i + 1;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int close = s.indexOf(c, i + 1);
                if (close < 0) throw fail(new Token(Kind.END, "", column), "has an unclosed string");
                out.add(new Token(Kind.STRING, s.substring(i + 1, close), column));
                i = close + 1;
            } else if (Character.isLetter(c)) {
                int end = i + 1;
                while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) end++;
                String word = s.substring(i, end);
                Kind kind =
                        switch (word) {
                            case "true" -> Kind.TRUE;
                            case "false" -> Kind.FALSE;
                            case "in" -> Kind.IN;
                            default -> Kind.KEY;
                        };
                out.add(new Token(kind, word, column));
                i = end;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                @Nullable
                Kind pair =
                        switch (two) {
                            case "==" -> Kind.EQ;
                            case "!=" -> Kind.NE;
                            case "&&" -> Kind.AND;
                            case "||" -> Kind.OR;
                            default -> null;
                        };
                if (pair != null) {
                    out.add(new Token(pair, two, column));
                    i += 2;
                    continue;
                }
                Kind one =
                        switch (c) {
                            case '!' -> Kind.NOT;
                            case '(' -> Kind.LPAREN;
                            case ')' -> Kind.RPAREN;
                            case '[' -> Kind.LBRACKET;
                            case ']' -> Kind.RBRACKET;
                            case ',' -> Kind.COMMA;
                            default ->
                                throw fail(
                                        new Token(Kind.END, "", column),
                                        "has '" + c
                                                + "', which is not part of the language (==, !=, in [...], &&, ||, !, parentheses)");
                        };
                out.add(new Token(one, String.valueOf(c), column));
                i++;
            }
        }
        out.add(new Token(Kind.END, "", s.length() + 1));
        return out;
    }
}
