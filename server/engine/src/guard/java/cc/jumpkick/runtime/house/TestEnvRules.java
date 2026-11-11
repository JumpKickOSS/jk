// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SearchCeiling;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.guard.api.Allow;
import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.wire.EnginePaths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Every environment variable a test reads reaches the JVM it runs in. jk forks a test JVM from an
 * allow-list, not from the shell, so a name its module does not forward reads as unset: a capture
 * mode that never captures, a tool home that skips its test on every machine. The names come out
 * of the test sources; what reaches comes out of each module's manifest and the engine's own
 * allow-list, never restated.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class TestEnvRules {

    /** A read by name: the JDK's own, and the host facade that wraps it. */
    private static final Pattern GETENV = Pattern.compile("\\b(?:System\\.getenv|ProcessEnv\\.get)\\s*\\(");

    /** {@code System.getenv().get("X")} and {@code getOrDefault("X", …)}: a named read through the map. */
    private static final Pattern MAP_READ = Pattern.compile("\\s*\\.\\s*(?:get|getOrDefault)\\s*\\(\\s*\"([^\"]+)\"");

    /** JUnit's conditions read the environment of the JVM that evaluates them, the test JVM. */
    private static final Pattern CONDITION = Pattern.compile("@(?:Enabled|Disabled)IfEnvironmentVariable\\b");

    private static final Pattern NAMED = Pattern.compile("named\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern CONSTANT = Pattern.compile("(?:([A-Z]\\w*)\\.)?([A-Z][A-Z0-9_]*)");

    /**
     * What the launcher sets on every test JVM beyond the names it inherits: the JDK it runs under
     * ({@code WorkerEnv}), the nested engine's listeners and self-update switch and the search
     * ceilings ({@code TestEnv.forModule}), and the fast tier's walls ({@code TestLaunch}).
     */
    private static final Set<String> LAUNCHER = Set.of(
            "JAVA_HOME",
            "JK_HTTP_ENABLED",
            "JK_HTTP_PORT",
            SelfUpdatePolicy.ENV,
            SearchCeiling.ENV,
            Http.DENY_HOSTS_ENV,
            EnginePaths.NO_SPAWN_ENV);

    /** A module that pins Node.js runs its tests with that Node.js named ({@code TestLaunch}). */
    private static final String NODE_HOME = "NODE_HOME";

    /**
     * Reads whose name is computed, keyed {@code <file> | <argument>}, with every name each can
     * take; those names are judged like a literal. An entry no read uses is red.
     */
    private static final Map<String, Set<String>> COMPUTED = Map.of(
            "clients/cli/src/test/java/cc/jumpkick/cli/api/ClientEnvForwardTest.java | e.getKey()",
            Set.copyOf(BuildEnv.MACHINE),
            "clients/cli/src/test/java/cc/jumpkick/command/project/TemplateCatalogE2eTest.java | var",
            Set.of("ANDROID_HOME", "ANDROID_SDK_ROOT"),
            "clients/intellij/src/test/java/cc/jumpkick/idea/InstalledJk.java | name",
            Set.of("JK_HOME", "JK_STORE_DIR"),
            "server/engine/src/test/java/cc/jumpkick/runtime/TrainAotCacheE2eTest.java | env",
            Set.of("GRAALVM_HOME", "JAVA_HOME", "USERPROFILE", "HOME"),
            // The store lookup masks JK_HOME and JK_STORE_DIR, the only names a store root asks for.
            "server/resolver/src/test/java/cc/jumpkick/resolver/QuarkusLockPerfTest.java | k",
            Set.of(),
            // Both iterate the names this JVM already has.
            "shared/core/src/test/java/cc/jumpkick/config/BuildEnvToolchainTest.java | k",
            Set.of(),
            "shared/core/src/test/java/cc/jumpkick/config/BuildEnvToolchainTest.java | name",
            Set.of());

    private record Read(String file, int line, String name, boolean computed) {}

    /** The names a module's test JVM is handed, beyond the defaults every test JVM gets. */
    private record Reach(boolean everything, Set<String> names) {

        boolean has(String name) {
            return everything || names.contains(name) || reachesEveryTestJvm(name);
        }
    }

    @Guard(
            id = "test-env-forwarded",
            why =
                    "jk forks a test JVM from an allow-list, not the shell: a variable a test reads that its module does not forward is always unset, so a capture mode never writes and a tool-home test always skips",
            instead =
                    "forward the bare name in the module's [test] env (the value enters the suite key only when set), or read it through a property the harness sets")
    @Allow(
            in = "server/engine/src/test/java/cc/jumpkick/engine/plugin/EchoPluginMain.java",
            reason = "a plugin child process: it reads the environment its test hands the child")
    @Allow(
            in = "server/engine/src/test/java/cc/jumpkick/runtime/base/KtsReaderCarrierProbe.java",
            reason = "a child JVM: it reads the CLASSPATH its test hands the child")
    @Allow(
            in = "shared/plugin-sdk/src/test/java/cc/jumpkick/plugin/build/EnvEchoMain.java",
            reason = "a tool child process: it reads the environment its test hands the child")
    @Allow(
            in = "plugins/generator/src/test/java/cc/jumpkick/generate/StubTool.java",
            reason = "a generator child process: it reads the environment its test hands the child")
    @Allow(
            in = "server/engine/src/test/java/cc/jumpkick/runtime/KeyProbesTest.java | PROBE_SECRET",
            reason = "read by the key-probe child process the test forks, from the environment the test hands it")
    @Allow(
            in = "clients/cli/src/test/java/cc/jumpkick/command/Giter8Catalog.java | JK_TEMPLATES",
            reason = "a developer's template override must not steer the catalogue under test; unset by design")
    @Allow(
            in = "server/io/src/test/java/cc/jumpkick/repo/SettingsXmlCredentialStubTest.java | JK_SETTINGS_STUB_PW",
            reason = "the test asserts the serving process does not have it; only the requesting shell does")
    void testEnvForwarded(Model model, Text text, Violations v) {
        Set<String> rootProfiles = profileNames(JkBuildParser.parse(String.join("\n", text.lines("jk.toml"))));
        Map<String, List<String>> sourcesByType = new HashMap<>();
        for (String f : text.files("**/src/**/*.java")) {
            String simple = f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length());
            sourcesByType.computeIfAbsent(simple, k -> new ArrayList<>()).add(f);
        }
        Set<String> usedComputed = new HashSet<>();
        int reads = 0;
        for (String module : model.modules()) {
            String prefix = module.isEmpty() ? "" : module + "/";
            String manifest = prefix + "jk.toml";
            JkBuild build = JkBuildParser.parse(String.join("\n", text.lines(manifest)));
            Set<String> names = new HashSet<>(rootProfiles);
            names.addAll(profileNames(build));
            for (EnvDecl d : build.build().testEnv()) names.add(d.name());
            names.addAll(build.build().env().declared());
            if (!build.project().nodeSpec().isEmpty()) names.add(NODE_HOME);
            Reach own = new Reach(build.build().env().inherit(), names);
            for (String set : List.of("test", "fixtures")) {
                boolean fixtures = set.equals("fixtures");
                for (String ext : List.of("java", "kt")) {
                    for (String rel : text.files(prefix + "src/" + set + "/**/*." + ext)) {
                        for (Read r : reads(text, rel, sourcesByType)) {
                            reads++;
                            Set<String> taken;
                            if (r.computed()) {
                                String key = r.file() + " | " + r.name();
                                Set<String> known = COMPUTED.get(key);
                                if (known == null) {
                                    v.add(
                                            new TextSite(r.file(), r.line(), "getenv(" + r.name() + ")"),
                                            "the name this read takes is computed, so no check can see it:"
                                                    + " list the names it can take in TestEnvRules.COMPUTED under \""
                                                    + key + "\"");
                                    continue;
                                }
                                usedComputed.add(key);
                                taken = known;
                            } else {
                                taken = Set.of(r.name());
                            }
                            for (String name : taken) {
                                if (fixtures ? reachesEveryTestJvm(name) : own.has(name)) continue;
                                v.add(
                                        new TextSite(r.file(), r.line(), name),
                                        fixtures
                                                ? name + " is read by a test fixture, which runs in the test JVM of"
                                                        + " every module that consumes it; read it through a property"
                                                        + " the harness sets, or a name every test JVM inherits"
                                                : name + " never reaches " + module + "'s test JVM: add \"" + name
                                                        + "\" to [test] env in " + manifest);
                            }
                        }
                    }
                }
            }
        }
        for (String key : new TreeSet<>(COMPUTED.keySet())) {
            if (usedComputed.contains(key)) continue;
            String file = key.substring(0, key.indexOf(" | "));
            v.add(
                    new TextSite(file, 0, key),
                    "no read in " + file + " takes this argument any more: drop its TestEnvRules.COMPUTED entry");
        }
        if (reads < 30)
            throw new IllegalStateException(
                    "environment reads found in test sources: " + reads + " — 47 when measured; the walk moved");
        v.population(reads);
    }

    /** Inherited from the engine (the allow-list on either platform's spelling) or set by the launcher. */
    private static boolean reachesEveryTestJvm(String name) {
        return WorkerEnv.allowed(name, false) || WorkerEnv.allowed(name, true) || LAUNCHER.contains(name);
    }

    private static Set<String> profileNames(JkBuild build) {
        Set<String> out = new HashSet<>();
        for (Profile p : build.profiles().byName().values()) out.addAll(p.env().keySet());
        return out;
    }

    /** Every environment read in one source file: by name, through the map, or by a JUnit condition. */
    private static List<Read> reads(Text text, String rel, Map<String, List<String>> sourcesByType) {
        String code = text.blanked(rel, Blank.COMMENTS_AND_STRINGS);
        if (!code.contains("getenv") && !code.contains("ProcessEnv.get") && !code.contains("IfEnvironmentVariable"))
            return List.of();
        String src = text.blanked(rel, Blank.COMMENTS);
        if (src.length() != code.length())
            throw new IllegalStateException(rel + ": the two blanked views differ in length; offsets do not line up");
        List<Read> out = new ArrayList<>();
        Matcher m = GETENV.matcher(code);
        while (m.find()) {
            int open = m.end();
            int close = closing(code, open);
            int line = line(code, m.start());
            if (close < 0) {
                out.add(new Read(
                        rel,
                        line,
                        code.substring(open, Math.min(code.length(), open + 40)).strip(),
                        true));
                continue;
            }
            String arg = src.substring(open, close).strip();
            if (arg.isEmpty()) {
                Matcher map = MAP_READ.matcher(src).region(close + 1, src.length());
                if (map.lookingAt()) out.add(new Read(rel, line, map.group(1), false));
                continue;
            }
            String name = resolve(text, src, arg, sourcesByType);
            out.add(name != null ? new Read(rel, line, name, false) : new Read(rel, line, arg, true));
        }
        Matcher c = CONDITION.matcher(code);
        while (c.find()) {
            int open = code.indexOf('(', c.end());
            int close = open < 0 ? -1 : closing(code, open + 1);
            Matcher named = close < 0 ? null : NAMED.matcher(src.substring(open, close));
            if (named != null && named.find()) out.add(new Read(rel, line(code, c.start()), named.group(1), false));
        }
        return out;
    }

    /** The name {@code arg} spells: a literal, a constant of this file, or {@code Type.CONSTANT}; else null. */
    private static @Nullable String resolve(
            Text text, String src, String arg, Map<String, List<String>> sourcesByType) {
        Matcher lit = LITERAL.matcher(arg);
        if (lit.matches()) return lit.group(1);
        Matcher constant = CONSTANT.matcher(arg);
        if (!constant.matches()) return null;
        Pattern declared = Pattern.compile("\\b" + constant.group(2) + "\\s*=\\s*\"([^\"]+)\"");
        if (constant.group(1) == null) {
            Matcher d = declared.matcher(src);
            return d.find() ? d.group(1) : null;
        }
        for (String owner : sourcesByType.getOrDefault(constant.group(1), List.of())) {
            Matcher d = declared.matcher(text.blanked(owner, Blank.COMMENTS));
            if (d.find()) return d.group(1);
        }
        return null;
    }

    /** The offset of the parenthesis closing the one before {@code from}, or -1 within a short reach. */
    private static int closing(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length() && i < from + 400; i++) {
            char ch = code.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int line(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }
}
