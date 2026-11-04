// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.task.RunNotices;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The values of a {@code settings.xml} as one request sees them: Maven's {@code ${...}}
 * expressions expanded the way Maven expands them.
 *
 * <p>{@code ${env.NAME}} reads the request's environment, which inside the engine is the shell
 * that ran {@code jk}, not the one that started the engine. Any other expression names a system
 * property: {@code ${user.home}}, {@code ${os.name}}, {@code ${java.home}} and the rest of the
 * JVM's, with {@code ${user.dir}} the request's working directory and {@code ${maven.home}} the
 * installation {@code M2_HOME} / {@code MAVEN_HOME} names. {@code ${settings.*}} and {@code
 * ${project.*}} are not expanded, as in Maven. The file is the user's own, so any variable may be
 * named.
 *
 * <p>An expression that does not resolve stays in the value as written, as Maven leaves it, and
 * is reported once per run naming the file, the element and the expression: a password sent as
 * {@code ${env.NEXUS_PASSWORD}} answers 401, and a silent 401 is the thing to avoid.
 *
 * <p>An encrypted password ({@code {...}}, see {@link MavenPasswords}) is decrypted after
 * expansion, as Maven decrypts it, with the master password of {@code settings-security.xml}: the
 * file {@code JK_M2_SETTINGS_SECURITY} names in the request's environment, else {@code
 * ~/.m2/settings-security.xml}. The file is read only when a value is encrypted.
 *
 * <p>Every lookup is remembered, the security files too, so a later request can ask {@link
 * #stillHolds} whether the values it would see are the ones this expansion saw and reuse its
 * result.
 */
final class SettingsValues {

    /** {@code ${...}}: Maven's expression syntax; the body is anything up to the first {@code }}. */
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

    /** A once-per-run warning this expansion has to say. */
    record Notice(String key, String message) {

        void say() {
            RunNotices.warnOnce(key, () -> message);
        }
    }

    /** One expanded value and the expressions in it that did not resolve. */
    record Expanded(String value, List<String> unresolved) {}

    private final Function<String, @Nullable String> env;
    private final Function<String, @Nullable String> properties;

    /** Every lookup this expansion made and what it answered, in order. */
    private final Map<String, @Nullable String> seen = new LinkedHashMap<>();

    private final Set<Notice> notices = new LinkedHashSet<>();

    /** Every security file the master password was read through, with its identity then. */
    private final Map<Path, StampedMemo.@Nullable FileStamp> securityFiles = new LinkedHashMap<>();

    /** The master password, or why there is none; unread until a value is encrypted. */
    private @Nullable String master;

    private MavenPasswords.@Nullable Undecryptable noMaster;

    SettingsValues(Function<String, @Nullable String> env, Function<String, @Nullable String> properties) {
        this.env = Objects.requireNonNull(env, "env");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** Expansion against {@code env} and this JVM's system properties, as the current request sees them. */
    static SettingsValues of(Function<String, @Nullable String> env) {
        return new SettingsValues(env, name -> systemProperty(name, env));
    }

    /**
     * The system property {@code name} as Maven would expose it to this request: {@code user.dir}
     * is the request's working directory, {@code maven.home} the installation the environment
     * names, everything else this JVM's own.
     */
    private static @Nullable String systemProperty(String name, Function<String, @Nullable String> env) {
        return switch (name) {
            case "user.dir" -> {
                Path dir = SessionContext.current().workingDir();
                yield dir.toAbsolutePath().toString();
            }
            case "maven.home" -> {
                String m2 = env.apply("M2_HOME");
                yield m2 != null && !m2.isBlank() ? m2.strip() : blankToNull(env.apply("MAVEN_HOME"));
            }
            default -> System.getProperty(name);
        };
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * {@code raw} with every expression expanded; an unresolved one is left as written and
     * reported once against {@code file} and {@code element} ({@code <server> `nexus` <password>}).
     */
    String expand(Path file, String element, String raw) {
        Expanded expanded = expandQuietly(raw);
        for (String expression : expanded.unresolved()) {
            notices.add(new Notice(
                    "m2-settings-unexpanded:" + file + " " + element + " " + expression,
                    "jk: warning: " + file + " " + element + " names ${" + expression + "}, " + why(expression)
                            + "; it is used as written"));
        }
        return expanded.value();
    }

    /** {@code raw} expanded, with what did not resolve listed rather than reported. */
    Expanded expandQuietly(String raw) {
        if (!raw.contains("${")) return new Expanded(raw, List.of());
        Matcher m = EXPRESSION.matcher(raw);
        StringBuilder out = new StringBuilder();
        List<String> unresolved = new ArrayList<>();
        while (m.find()) {
            String expression = m.group(1);
            String value = resolve(expression);
            if (value == null) unresolved.add(expression);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return new Expanded(out.toString(), List.copyOf(unresolved));
    }

    private static String why(String expression) {
        if (expression.startsWith("settings.") || expression.startsWith("project.")) {
            return "which settings.xml does not expand";
        }
        if (expression.startsWith("env.")) {
            return "and " + expression.substring("env.".length()) + " is not set in the shell that ran jk";
        }
        return "which is not a system property";
    }

    /** The value {@code expression} expands to, or null when it does not resolve. */
    private @Nullable String resolve(String expression) {
        if (expression.startsWith("settings.") || expression.startsWith("project.")) return null;
        String value = lookup(expression);
        seen.put(expression, value);
        return value;
    }

    private @Nullable String lookup(String expression) {
        if (expression.startsWith("env.")) return env.apply(expression.substring("env.".length()));
        return properties.apply(expression);
    }

    /** Whether every lookup this expansion made answers the same through {@code other}. */
    boolean stillHolds(SettingsValues other) {
        for (Map.Entry<String, @Nullable String> e : seen.entrySet()) {
            if (!Objects.equals(other.lookup(e.getKey()), e.getValue())) return false;
        }
        for (Map.Entry<Path, StampedMemo.@Nullable FileStamp> e : securityFiles.entrySet()) {
            if (!Objects.equals(StampedMemo.FileStamp.of(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    /**
     * {@code value} — already expanded — decrypted when it is an encrypted Maven password, and
     * as it is when it is not.
     *
     * @throws MavenPasswords.Undecryptable with the reason, never the value
     */
    String decrypt(String value) throws MavenPasswords.Undecryptable {
        String payload = MavenPasswords.payload(value);
        if (payload == null) return value;
        return MavenPasswords.decrypt(payload, master());
    }

    /** The master password of the request's security file, read once per expansion. */
    private String master() throws MavenPasswords.Undecryptable {
        if (master != null) return master;
        if (noMaster != null) throw noMaster;
        Set<Path> read = new LinkedHashSet<>();
        try {
            master = MavenPasswords.readMaster(securityFile(), home(), read);
            return master;
        } catch (MavenPasswords.Undecryptable e) {
            noMaster = e;
            throw e;
        } finally {
            for (Path file : read) securityFiles.put(file, StampedMemo.FileStamp.of(file));
        }
    }

    /** {@code JK_M2_SETTINGS_SECURITY} as the request has it, else {@code ~/.m2/settings-security.xml}. */
    private Path securityFile() throws MavenPasswords.Undecryptable {
        String named = resolve("env." + MavenPasswords.SECURITY_ENV);
        Path home = home();
        if (named != null && !named.isBlank()) {
            String cwd = resolve("user.dir");
            return MavenPasswords.resolve(
                    cwd == null ? null : Path.of(cwd), named.strip(), home, MavenPasswords.SECURITY_ENV);
        }
        return home.resolve(".m2").resolve("settings-security.xml");
    }

    private Path home() throws MavenPasswords.Undecryptable {
        String home = resolve("user.home");
        if (home == null || home.isBlank()) throw new MavenPasswords.Undecryptable("there is no home directory");
        return Path.of(home);
    }

    /** Report {@code notice} with what the expansion found. */
    void report(Notice notice) {
        notices.add(notice);
    }

    /** What this expansion has to report, in the order it found it. */
    List<Notice> notices() {
        return List.copyOf(notices);
    }
}
