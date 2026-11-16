// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JUnit Platform line a Jupiter runs on. Jupiter 5.x.y ships with Platform 1.x.y; from
 * Jupiter 6 the two share one number. A Platform 6 launcher beside a Jupiter 5 engine drops the
 * engine without a word and the run reports no tests, so the launcher {@code jk lock} injects
 * follows the Jupiter the test graph resolves, declared or transitive ({@link TestEngines}), and a
 * lock whose lines still disagree — a launcher the module declares on another line — is refused.
 */
public final class JupiterLine {

    private JupiterLine() {}

    static final String JUPITER_GROUP = "org.junit.jupiter";
    public static final String LAUNCHER = "org.junit.platform:junit-platform-launcher";
    public static final String ENGINE = JUPITER_GROUP + ":junit-jupiter-engine";
    public static final String API = JUPITER_GROUP + ":junit-jupiter-api";
    public static final String PLATFORM_ENGINE = "org.junit.platform:junit-platform-engine";

    /** The Platform version Jupiter {@code version} ships with: {@code 5.9.0 -> 1.9.0}, {@code 6.1.3 -> 6.1.3}. */
    public static String platformVersion(String version) {
        return version.startsWith("5.") ? "1." + version.substring(2) : version;
    }

    /**
     * The selector the launcher seeding the first test solve rides: the declared Jupiter's selector
     * ({@link #declaredJupiter}) moved onto the Platform line ({@code 5.9.0 -> 1.9.0}, {@code ^5.9
     * -> ^1.9}), or {@code latest} when the module declares no Jupiter, or one whose selector names
     * no line (a range, latest).
     */
    public static VersionSelector launcherSelector(JkBuild project) {
        Dependency jupiter = declaredJupiter(project);
        return jupiter == null ? latest() : onLine(jupiter.version(), true);
    }

    /**
     * The selector an injected engine of another framework (Vintage), and the Jupiter added to a
     * module with no test dependencies, seed the first test solve with: the declared Jupiter's own
     * line ({@link #declaredJupiter}), which the engines share, or {@code latest} when the module
     * declares no Jupiter.
     */
    public static VersionSelector engineSelector(JkBuild project) {
        Dependency jupiter = declaredJupiter(project);
        return jupiter == null ? latest() : onLine(jupiter.version(), false);
    }

    private static VersionSelector onLine(VersionSelector declared, boolean platform) {
        return switch (declared) {
            case VersionSelector.Exact e ->
                VersionSelector.parse(platform ? platformVersion(e.version()) : e.version());
            case VersionSelector.Caret c ->
                VersionSelector.parse("^" + (platform ? platformVersion(c.version()) : c.version()));
            case VersionSelector.Tilde t ->
                VersionSelector.parse("~" + (platform ? platformVersion(t.version()) : t.version()));
            default -> latest();
        };
    }

    private static VersionSelector latest() {
        return VersionSelector.parse("latest");
    }

    /**
     * The Jupiter version a module's JUnit release train follows: the version its declared Jupiter
     * names ({@link #declaredJupiter}; an exact, caret or tilde selector's number), else the one
     * {@code managed} (a platform table, module to version) holds for a Jupiter artifact, else null
     * (no Jupiter declared or managed: an implicit one, or a selector that names no line).
     */
    public static @Nullable String trainVersion(JkBuild project, Map<String, String> managed) {
        Dependency jupiter = declaredJupiter(project);
        if (jupiter != null) {
            String declared =
                    switch (jupiter.version()) {
                        case VersionSelector.Exact e -> e.version();
                        case VersionSelector.Caret c -> c.version();
                        case VersionSelector.Tilde t -> t.version();
                        default -> null;
                    };
            if (declared != null && !declared.isBlank()) return declared;
        }
        for (var e : managed.entrySet()) {
            if (e.getKey().startsWith(JUPITER_GROUP + ":junit-jupiter")) return e.getValue();
        }
        return null;
    }

    /**
     * {@code pins} (package key or {@code group:artifact} to version, as a prior lock held them)
     * without the JUnit pins off the release train of Jupiter {@code jupiter}: a Jupiter or Vintage
     * artifact on another major, a Platform artifact on another major than {@link #platformVersion}'s.
     * JUnit ships as one train, and a pin a lock kept from another line (an implicit Jupiter 6 before
     * the module declared 5.12.2) beside the declared Jupiter fails every test with a
     * NoSuchMethodError: the declared Jupiter decides the train, as it would in a fresh lock.
     */
    public static Map<String, String> onTrain(Map<String, String> pins, String jupiter) {
        String jupiterMajor = major(jupiter);
        String platformMajor = major(platformVersion(jupiter));
        Map<String, String> out = null;
        for (var e : pins.entrySet()) {
            String key = e.getKey();
            String want;
            if (key.startsWith(JUPITER_GROUP + ":") || key.startsWith("org.junit.vintage:")) want = jupiterMajor;
            else if (key.startsWith("org.junit.platform:")) want = platformMajor;
            else continue;
            if (major(e.getValue()).equals(want)) continue;
            if (out == null) out = new HashMap<>(pins);
            out.remove(key);
        }
        return out == null ? pins : out;
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /**
     * The launcher version a managed Jupiter implies when nothing manages the launcher itself: the
     * Platform line of the first Jupiter artifact {@code managed} (module to version) holds, or null.
     */
    public static @Nullable String launcherForManaged(Map<String, String> managed) {
        for (var e : managed.entrySet()) {
            if (e.getKey().startsWith(JUPITER_GROUP + ":junit-jupiter")) return platformVersion(e.getValue());
        }
        return null;
    }

    /**
     * The declared Jupiter artifact — aggregate, api, engine or params — on the module's test
     * classpath, or null: a test-scope one first, else one the module's main or provided
     * dependencies declare ({@link TestEngines#CLASSPATH_SCOPES}), which Maven's test classpath
     * holds as well.
     */
    static @Nullable Dependency declaredJupiter(JkBuild project) {
        List<Scope> scopes = new ArrayList<>(List.of(Scope.TEST, Scope.TEST_DEV));
        scopes.addAll(TestEngines.CLASSPATH_SCOPES);
        for (Scope scope : scopes) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (d.module().startsWith(JUPITER_GROUP + ":junit-jupiter") && !d.isFile()) return d;
            }
        }
        return null;
    }
}
