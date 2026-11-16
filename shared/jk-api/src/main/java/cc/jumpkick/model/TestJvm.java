// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.time.DateTimeException;
import java.time.ZoneId;
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
 * @param timezone the zone id the test JVM's default time zone is ({@value #DEFAULT_TIMEZONE} unless
 *     the module says otherwise), or {@value #INHERIT_TIMEZONE}: the zone of the shell that ran {@code
 *     jk}, which the suite is then keyed on
 */
public record TestJvm(List<String> jvmArgs, Map<String, String> systemProperties, String locale, String timezone) {

    /**
     * The locale every test JVM runs in unless the module says otherwise: a laptop on {@code
     * de_DE.UTF-8}, a runner on {@code C.UTF-8} and a developer on {@code en_US.UTF-8} run a suite
     * alike and share its result.
     */
    public static final String DEFAULT_LOCALE = "en-US";

    /** {@code [test] locale = "inherit"}: the caller's locale variables reach the test JVM and key the suite. */
    public static final String INHERIT_LOCALE = "inherit";

    /**
     * The time zone every test JVM runs in unless the module says otherwise: a suite's result
     * depends on the code under test, not on where the machine that ran it sits.
     */
    public static final String DEFAULT_TIMEZONE = "UTC";

    /** {@code [test] timezone = "inherit"}: the caller's zone reaches the test JVM and keys the suite. */
    public static final String INHERIT_TIMEZONE = "inherit";

    public static final TestJvm EMPTY = new TestJvm(List.of(), Map.of());

    public TestJvm {
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        systemProperties = systemProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(systemProperties));
        locale = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        timezone = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone;
    }

    /** Flags and properties in the default locale and time zone. */
    public TestJvm(List<String> jvmArgs, Map<String, String> systemProperties) {
        this(jvmArgs, systemProperties, DEFAULT_LOCALE, DEFAULT_TIMEZONE);
    }

    public boolean isEmpty() {
        return jvmArgs.isEmpty()
                && systemProperties.isEmpty()
                && locale.equals(DEFAULT_LOCALE)
                && timezone.equals(DEFAULT_TIMEZONE);
    }

    /** Whether the test JVM takes the caller's locale ({@value #INHERIT_LOCALE}) rather than a fixed one. */
    public boolean inheritsLocale() {
        return locale.equals(INHERIT_LOCALE);
    }

    /** Whether the test JVM takes the caller's time zone ({@value #INHERIT_TIMEZONE}) rather than a fixed one. */
    public boolean inheritsTimezone() {
        return timezone.equals(INHERIT_TIMEZONE);
    }

    /** These settings with {@code properties} in place of the declared system properties. */
    public TestJvm withSystemProperties(Map<String, String> properties) {
        return new TestJvm(jvmArgs, properties, locale, timezone);
    }

    /**
     * The command-line form: the locale's {@code -Duser.*} properties and the zone's {@code
     * -Duser.timezone} first, so the same flag among the module's own wins, then the args, then one
     * {@code -Dkey=value} per property.
     */
    public List<String> flags() {
        List<String> out = new ArrayList<>(localeFlags(locale));
        if (!inheritsTimezone()) out.add("-Duser.timezone=" + timezone);
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

    /** Whether {@code id} is a {@code [test] timezone} value: {@value #INHERIT_TIMEZONE} or a zone id ({@code UTC}, {@code Europe/Berlin}). */
    public static boolean validTimezone(String id) {
        if (id.equals(INHERIT_TIMEZONE)) return true;
        try {
            ZoneId.of(id);
            return true;
        } catch (DateTimeException notAZone) {
            return false;
        }
    }
}
