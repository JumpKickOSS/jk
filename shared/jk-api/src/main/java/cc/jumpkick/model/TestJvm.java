// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code [test] jvm-args}, {@code [test] system-properties} and {@code [test] locale}: what every
 * forked test JVM's command line carries beyond jk's own tuning, in manifest order. All are run-tests
 * inputs; a profile's {@code jvm-args} follow them, so the profile wins where the two disagree.
 *
 * @param jvmArgs flags appended verbatim ({@code -Xmx1g}, {@code --add-opens …}, an agent)
 * @param systemProperties {@code key -> value}, each forked as {@code -Dkey=value}
 * @param locale the BCP 47 tag the test JVM's default locale is ({@value #DEFAULT_LOCALE} unless the
 *     module says otherwise), or {@value #INHERIT_LOCALE}: the locale of the shell that ran {@code
 *     jk}, which the suite is then keyed on
 */
public record TestJvm(List<String> jvmArgs, Map<String, String> systemProperties, String locale) {

    /**
     * The locale every test JVM runs in unless the module says otherwise: a laptop on {@code
     * de_DE.UTF-8}, a runner on {@code C.UTF-8} and a developer on {@code en_US.UTF-8} run a suite
     * alike and share its result.
     */
    public static final String DEFAULT_LOCALE = "en-US";

    /** {@code [test] locale = "inherit"}: the caller's locale variables reach the test JVM and key the suite. */
    public static final String INHERIT_LOCALE = "inherit";

    public static final TestJvm EMPTY = new TestJvm(List.of(), Map.of());

    public TestJvm {
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        systemProperties = systemProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(systemProperties));
        locale = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
    }

    /** Flags and properties in the default locale. */
    public TestJvm(List<String> jvmArgs, Map<String, String> systemProperties) {
        this(jvmArgs, systemProperties, DEFAULT_LOCALE);
    }

    public boolean isEmpty() {
        return jvmArgs.isEmpty() && systemProperties.isEmpty() && locale.equals(DEFAULT_LOCALE);
    }

    /** Whether the test JVM takes the caller's locale ({@value #INHERIT_LOCALE}) rather than a fixed one. */
    public boolean inheritsLocale() {
        return locale.equals(INHERIT_LOCALE);
    }

    /** These settings with {@code properties} in place of the declared system properties. */
    public TestJvm withSystemProperties(Map<String, String> properties) {
        return new TestJvm(jvmArgs, properties, locale);
    }

    /**
     * The command-line form: the locale's {@code -Duser.*} properties first, so a {@code
     * -Duser.language} among the module's own flags wins, then the args, then one {@code
     * -Dkey=value} per property.
     */
    public List<String> flags() {
        List<String> out = new ArrayList<>(localeFlags(locale));
        out.addAll(jvmArgs);
        systemProperties.forEach((k, v) -> out.add("-D" + k + "=" + v));
        return List.copyOf(out);
    }

    /**
     * The system properties that make a JVM's default locale {@code tag}, in every category: {@code
     * -Duser.language} and, where the tag has them, {@code -Duser.country}, {@code -Duser.script}
     * and {@code -Duser.variant}. None for {@value #INHERIT_LOCALE}.
     */
    public static List<String> localeFlags(String tag) {
        if (tag.equals(INHERIT_LOCALE)) return List.of();
        Locale locale = Locale.forLanguageTag(tag);
        List<String> out = new ArrayList<>();
        out.add("-Duser.language=" + locale.getLanguage());
        if (!locale.getCountry().isEmpty()) out.add("-Duser.country=" + locale.getCountry());
        if (!locale.getScript().isEmpty()) out.add("-Duser.script=" + locale.getScript());
        if (!locale.getVariant().isEmpty()) out.add("-Duser.variant=" + locale.getVariant());
        return List.copyOf(out);
    }

    /** Whether {@code tag} is a {@code [test] locale} value: {@value #INHERIT_LOCALE} or a BCP 47 tag with a language. */
    public static boolean validLocale(String tag) {
        if (tag.equals(INHERIT_LOCALE)) return true;
        Locale locale = Locale.forLanguageTag(tag);
        return !locale.getLanguage().isEmpty() && locale.toLanguageTag().equalsIgnoreCase(tag);
    }
}
