// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One {@code ${...}} reference in a configuration value, as written.
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

    /**
     * {@code ${NAME}}, {@code ${env:NAME}}, {@code ${file:...}} or {@code ${cmd:...}}. A bare name
     * keeps the identifier shape so the generator vocabulary ({@code ${in}}, {@code ${module.dir}})
     * and a plugin's own placeholders are not references.
     */
    static final Pattern PATTERN =
            Pattern.compile("\\$\\{(?:env:([A-Za-z_][A-Za-z0-9_]*)|(file|cmd):([^}]*)|([A-Za-z_][A-Za-z0-9_]*))}");

    /** Every reference in {@code value}, in order of appearance, duplicates kept. Empty for null. */
    public static List<ConfigReference> all(@Nullable String value) {
        if (value == null || value.indexOf('$') < 0) return List.of();
        List<ConfigReference> out = new ArrayList<>();
        Matcher m = PATTERN.matcher(value);
        while (m.find()) out.add(of(m));
        return out;
    }

    /** The reference {@code m} just matched against {@link #PATTERN}. */
    static ConfigReference of(Matcher m) {
        String spelling = m.group();
        if (m.group(1) != null) return new ConfigReference(Scheme.ENV, m.group(1), spelling);
        if (m.group(4) != null) return new ConfigReference(Scheme.ENV, m.group(4), spelling);
        Scheme scheme = "file".equals(m.group(2)) ? Scheme.FILE : Scheme.CMD;
        return new ConfigReference(scheme, m.group(3).strip(), spelling);
    }

    /** True for {@code ${NAME}} and {@code ${env:NAME}}. */
    public boolean isEnv() {
        return scheme == Scheme.ENV;
    }
}
