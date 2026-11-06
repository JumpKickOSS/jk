// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The command line that reproduces a set of answers without asking: what a summary prints after a
 * form is answered. A field with a {@link Flag} is spelled with it; every other field is
 * {@code -p key=value}. Fields that are hidden, or whose value is their default, are left out,
 * since the command would get the same value without them.
 */
public final class FormCommand {

    /** Characters that need no quoting in a POSIX shell word. */
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_./:@%+=,-]+");

    private FormCommand() {}

    /** How one field is spelled on the command line. */
    public sealed interface Flag {
        /** A bare argument after the command, in form order. */
        record Positional() implements Flag {}

        /**
         * {@code name value}; a {@code bool} is {@code name} when true and {@code name=false} when
         * false.
         */
        record Named(String name) implements Flag {}

        static Flag positional() {
            return new Positional();
        }

        static Flag named(String name) {
            return new Named(name);
        }
    }

    /**
     * {@code command} followed by the arguments for {@code answers}, which {@link Form#check} must
     * accept. Positional arguments come first, then the rest in form order.
     */
    public static String render(
            String command, Form form, Map<String, ?> answers, Map<String, Flag> flags, DerivedDefaults derived) {
        FormCheck checked = form.check(answers, derived);
        if (!checked.ok()) {
            throw new FormException("answers do not settle the form: "
                    + checked.problems().getFirst().message());
        }
        List<String> positional = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (Field f : form.fields()) {
            Object value = checked.values().get(f.key());
            if (value == null || checked.hidden().contains(f.key())) continue;
            if (Objects.equals(value, Form.defaultOf(f, derived))) continue;
            switch (flags.get(f.key())) {
                case null -> named.addAll(List.of("-p", quote(f.key() + "=" + Values.text(value))));
                case Flag.Positional p -> positional.add(quote(Values.text(value)));
                case Flag.Named n -> {
                    if (value instanceof Boolean on) {
                        named.add(on ? n.name() : n.name() + "=false");
                    } else {
                        named.addAll(List.of(n.name(), quote(Values.text(value))));
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder(command);
        for (String word : positional) out.append(' ').append(word);
        for (String word : named) out.append(' ').append(word);
        return out.toString();
    }

    /** A POSIX shell word: bare when safe, else single-quoted. */
    public static String quote(String word) {
        if (BARE.matcher(word).matches()) return word;
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
