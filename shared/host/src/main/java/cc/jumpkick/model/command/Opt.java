// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model.command;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A command-line option (a {@code --flag} or {@code --name <value>}), declared as data rather than
 * a picocli annotation. The set of fields is exactly what jk's commands relied on from
 * {@code @Option}: multiple names, an optional value (with label), boolean flags, repeatable +
 * comma-split values, hidden, negatable ({@code --no-x}), required, and an optional-argument
 * fallback.
 *
 * <p>Build instances with {@link #flag} / {@link #value} and the {@code with*} tweaks, so a
 * command's {@code options()} reads as a short declarative list.
 *
 * <p>Primary {@link #names()} appear in help; {@link #aliases()} are accepted by the parser but
 * never shown (aliases are always hidden).
 *
 * @param names primary option names, e.g. {@code ["-q", "--quiet"]}; the longest {@code --} form is
 *     canonical
 * @param paramLabel label for the value in help, or {@code null} for a boolean flag
 * @param description help text
 * @param takesValue true when the option consumes a value (false ⇒ boolean flag)
 * @param repeatable true when the option may appear multiple times (List value)
 * @param split delimiter to split a single value into many (e.g. {@code ","}), or {@code null}
 * @param hidden true to omit the whole option from help
 * @param negatable true when a {@code --no-<name>} form is also accepted
 * @param required true when the option must be present
 * @param fallbackValue value used when the option appears without an argument (optional-arg), or
 *     {@code null}
 * @param aliases alternate names accepted by the parser but never shown in help
 */
public record Opt(
        List<String> names,
        @Nullable String paramLabel,
        String description,
        boolean takesValue,
        boolean repeatable,
        @Nullable String split,
        boolean hidden,
        boolean negatable,
        boolean required,
        @Nullable String fallbackValue,
        List<String> aliases) {

    public Opt {
        names = List.copyOf(names);
        aliases = List.copyOf(aliases);
    }

    /** A boolean flag option (no value), e.g. {@code --skip-tests}. */
    public static Opt flag(String description, String... names) {
        return new Opt(List.of(names), null, description, false, false, null, false, false, false, null, List.of());
    }

    /** A value option, e.g. {@code --profile <name>}. */
    public static Opt value(String paramLabel, String description, String... names) {
        return new Opt(
                List.of(names), paramLabel, description, true, false, null, false, false, false, null, List.of());
    }

    public Opt hide() {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                true,
                negatable,
                required,
                fallbackValue,
                aliases);
    }

    public Opt require() {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                hidden,
                negatable,
                true,
                fallbackValue,
                aliases);
    }

    public Opt negate() {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                hidden,
                true,
                required,
                fallbackValue,
                aliases);
    }

    public Opt repeat() {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                true,
                split,
                hidden,
                negatable,
                required,
                fallbackValue,
                aliases);
    }

    public Opt splitOn(String delimiter) {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                true,
                delimiter,
                hidden,
                negatable,
                required,
                fallbackValue,
                aliases);
    }

    /** Optional-argument option: present-without-value yields {@code fallback}. */
    public Opt withFallback(String fallback) {
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                hidden,
                negatable,
                required,
                fallback,
                aliases);
    }

    /**
     * Alternate names accepted by the parser but never shown in help. Aliases bind to the same
     * option identity (so unique-prefix resolution collapses them) and the same {@link
     * #canonicalName()}.
     */
    public Opt alias(String... extra) {
        if (extra.length == 0) return this;
        List<String> combined = new ArrayList<>(aliases.size() + extra.length);
        combined.addAll(aliases);
        for (String a : extra) combined.add(a);
        return new Opt(
                names,
                paramLabel,
                description,
                takesValue,
                repeatable,
                split,
                hidden,
                negatable,
                required,
                fallbackValue,
                combined);
    }

    /** Primary names plus aliases — every token the parser should accept for this option. */
    public List<String> allNames() {
        if (aliases.isEmpty()) return names;
        List<String> all = new ArrayList<>(names.size() + aliases.size());
        all.addAll(names);
        all.addAll(aliases);
        return List.copyOf(all);
    }

    /** The canonical (longest primary {@code --}) name, used as the lookup key in {@link Invocation}. */
    public String canonicalName() {
        String best = names.get(names.size() - 1);
        for (String n : names) {
            if (n.startsWith("--") && n.length() > best.length()) best = n;
        }
        return best.replaceFirst("^--?", "");
    }

    public boolean matches(String token) {
        return names.contains(token) || aliases.contains(token);
    }
}
