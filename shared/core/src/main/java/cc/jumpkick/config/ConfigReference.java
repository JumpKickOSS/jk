// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One {@code ${...}} reference in a configuration value, as written, and the one tokenizer every
 * {@code ${...}} of jk's configuration and of Maven's {@code settings.xml} is read by.
 *
 * <ul>
 * <li>{@code ${NAME}} or {@code ${env:NAME}} ({@link Scheme#ENV}): the environment variable
 * <li>{@code ${file:path}} ({@link Scheme#FILE}): the file's contents, trailing newline trimmed
 * <li>{@code ${file:path#key}} ({@link Scheme#FILE}): one key of a Java {@code .properties} file
 * <li>{@code ${cmd:argv}} ({@link Scheme#CMD}): a helper command's trimmed stdout
 * </ul>
 *
 * <p>The one answer to "what is a reference": the manifest guard, the credential resolver's
 * provenance rule and the expanders all parse through here, so a spelling one of them recognises is
 * a spelling all of them do. The body of a {@code file:} or {@code cmd:} reference runs to the first
 * closing brace, so neither a path nor a command may contain one.
 *
 * <h2>Two grammars, one tokenizer</h2>
 *
 * {@link #scan} and {@link #expand} find each {@code ${} and the first {@code }} after it, and hand
 * the text between to a grammar. A body the grammar does not read is not a token: the scan moves on
 * one character and the text stays as written. jk's grammar is {@link #parse} — the schemes above,
 * a bare name keeping the identifier shape so the generator vocabulary ({@code ${in}}, {@code
 * ${module.dir}}) and a plugin's own placeholders are not references. Maven's is {@link
 * #mavenExpression} — any non-empty body, {@code env.NAME} or a dotted property name, which {@code
 * settings.xml}'s reader resolves. Which file may use which scheme stays with the readers.
 */
public record ConfigReference(Scheme scheme, String body, String spelling) {

    /** Where a reference's value comes from. */
    public enum Scheme {
        ENV,
        FILE,
        CMD;

        /** The prefix as written: {@code env}, {@code file}, {@code cmd}. */
        public String prefix() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String OPEN = "${";

    /** Every reference in {@code value}, in order of appearance, duplicates kept. Empty for null. */
    public static List<ConfigReference> all(@Nullable String value) {
        return scan(value, ConfigReference::parse);
    }

    /**
     * {@code body}, the text between {@code ${} and {@code }}, under jk's grammar: {@code env:NAME},
     * {@code file:…}, {@code cmd:…} or a bare {@code NAME}; null when it is none of them.
     */
    public static @Nullable ConfigReference parse(String body) {
        String spelling = OPEN + body + "}";
        if (body.startsWith("env:")) {
            String name = body.substring("env:".length());
            return identifier(name) ? new ConfigReference(Scheme.ENV, name, spelling) : null;
        }
        if (body.startsWith("file:")) {
            return new ConfigReference(
                    Scheme.FILE, body.substring("file:".length()).strip(), spelling);
        }
        if (body.startsWith("cmd:")) {
            return new ConfigReference(
                    Scheme.CMD, body.substring("cmd:".length()).strip(), spelling);
        }
        return identifier(body) ? new ConfigReference(Scheme.ENV, body, spelling) : null;
    }

    /**
     * {@code body} under Maven's grammar: the expression itself ({@code env.NEXUS_PASSWORD},
     * {@code user.home}), or null when it is empty.
     */
    public static @Nullable String mavenExpression(String body) {
        return body.isEmpty() ? null : body;
    }

    /** {@code [A-Za-z_][A-Za-z0-9_]*}: an environment variable's name. */
    private static boolean identifier(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
            if (!letter && (i == 0 || c < '0' || c > '9')) return false;
        }
        return true;
    }

    /** Every token {@code grammar} reads in {@code value}, in order, duplicates kept. Empty for null. */
    public static <T> List<T> scan(@Nullable String value, Function<String, @Nullable T> grammar) {
        List<T> out = new ArrayList<>();
        if (value != null) tokenize(value, grammar, (token, spelling) -> out.add(token));
        return out;
    }

    /**
     * {@code value} with each token {@code grammar} reads replaced by what {@code resolve} answers
     * for it; a token {@code resolve} answers null for stays as written. {@code resolve} may throw,
     * and its exception is the caller's.
     */
    public static <T> String expand(
            String value, Function<String, @Nullable T> grammar, Function<T, @Nullable String> resolve) {
        if (!value.contains(OPEN)) return value;
        StringBuilder out = new StringBuilder(value.length());
        int[] copied = {0};
        tokenize(value, grammar, (token, span) -> {
            String replacement = resolve.apply(token);
            out.append(value, copied[0], span.start());
            out.append(replacement == null ? value.substring(span.start(), span.end()) : replacement);
            copied[0] = span.end();
        });
        return out.append(value, copied[0], value.length()).toString();
    }

    /** Where a token stands in the value: {@code start} at its {@code $}, {@code end} past its {@code }}. */
    private record Span(int start, int end) {}

    @FunctionalInterface
    private interface Visitor<T> {
        void token(T token, Span span);
    }

    private static <T> void tokenize(String value, Function<String, @Nullable T> grammar, Visitor<T> visitor) {
        int from = 0;
        while (true) {
            int start = value.indexOf(OPEN, from);
            if (start < 0) return;
            int close = value.indexOf('}', start + OPEN.length());
            if (close < 0) return;
            T token = grammar.apply(value.substring(start + OPEN.length(), close));
            if (token == null) {
                from = start + 1;
                continue;
            }
            visitor.token(token, new Span(start, close + 1));
            from = close + 1;
        }
    }

    /** True for {@code ${NAME}} and {@code ${env:NAME}}. */
    public boolean isEnv() {
        return scheme == Scheme.ENV;
    }
}
