// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * House rules whose corpus is {@code clients/cli} and the two IDE clients it owns. They were module
 * tests; as guards they keep their arms and their self-fail checks (an implausibly small scan
 * throws, so a blind arm is {@code scanner-failed}, never clean) and gain a {@code code}, the
 * baseline and the {@code ## Guards} rendering.
 */
@GuardSuite(scope = Scope.MODULE)
final class CliRules {

    private static final String MAIN = "clients/cli/src/main/java/**/*.java";
    private static final String TESTS = "clients/cli/src/test/java/**/*.java";
    private static final String IDEA_JAVA = "clients/intellij/src/main/java/";

    /** Types that drag the TOML parser or ANTLR into the native image's reachability graph. */
    private static final List<String> BANNED_PARSE_TYPES = List.of(
            "JkBuildParser",
            "PluginDescriptor",
            "PluginTableRegistry",
            "org.tomlj",
            "TomlValues",
            "LockFreshness",
            "LockManifestDigest",
            "PluginContributions",
            "Giter8LocalApply",
            "Giter8Apply",
            "Giter8ShortNames",
            "Giter8TemplateIndex",
            "org.stringtemplate",
            "org.antlr.runtime");

    private static TreeSet<String> matches(Pattern p, String text, int group) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(group));
        return out;
    }

    private static String raw(Text text, String path) {
        return text.blanked(path, Blank.NONE);
    }

    private static boolean exists(Text text, String path) {
        try {
            text.lines(path);
            return true;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    @Guard(
            id = "cli-no-parse-types",
            why =
                    "every parser or plugin-schema type named in CLI main drags tomlj or ANTLR into the native image's reachability graph",
            instead =
                    "ask the engine over the wire; TomlScan and MinimalToml are the line scanner and scalar codec the image is supposed to reach")
    void cliNamesNoParserOrPluginSchemaType(Text text, Violations v) {
        List<String> main = text.files(MAIN);
        if (main.size() < 200)
            throw new IllegalStateException(
                    "scan of clients/cli/src/main/java: " + main.size() + " files; measured against 274");
        for (String rel : main) {
            String code = raw(text, rel);
            for (String banned : BANNED_PARSE_TYPES) {
                if (code.contains(banned)) v.add(new TextSite(rel, 0, banned), rel + " names " + banned);
            }
        }
        v.population(main.size());
    }

    @Guard(
            id = "cli-engine-package-cycle",
            why =
                    "cc.jumpkick.cli.engine is the client half of the wire and depends on nothing that depends on it; an import of cli.run or cc.jumpkick.command closes a package cycle",
            instead = "whatever needs the import belongs on the other side of the line")
    void cliEngineImportsNeitherCliRunNorCommand(Text text, Violations v) {
        List<String> engine = text.files("clients/cli/src/main/java/cc/jumpkick/cli/engine/**/*.java");
        if (engine.size() < 20)
            throw new IllegalStateException(
                    "scan of cc/jumpkick/cli/engine: " + engine.size() + " files; measured against 27");
        Pattern edge = Pattern.compile(
                "^import (?:static )?cc\\.jumpkick\\.(cli\\.run|command)\\.[\\w.]+;", Pattern.MULTILINE);
        for (String rel : engine) {
            Matcher m = edge.matcher(raw(text, rel));
            while (m.find()) v.add(new TextSite(rel, 0, m.group()), rel + ": " + m.group());
        }
        v.population(engine.size());
    }

    @Guard(
            id = "cli-stdio-handoff-owner",
            why =
                    "handing this terminal to a child means restoring it out of jk's mode first, and that pair has one owner: CliOutput.handOffTerminal(pb); a child that inherits jk's raw mode gets no line editing of its own",
            instead = "CliOutput.handOffTerminal(pb)")
    void stdioIsInheritedOnlyByTheTerminalHandoffOwner(Text text, Violations v) {
        List<String> main = text.files(MAIN);
        if (main.size() < 200)
            throw new IllegalStateException(
                    "scan of clients/cli/src/main/java: " + main.size() + " files; measured against 285");
        String owner = null;
        for (String rel : main) if (rel.endsWith("/CliOutput.java")) owner = rel;
        if (owner == null)
            throw new IllegalStateException(
                    "CliOutput.java is the owner this rule exempts; it is not under src/main/java any more");
        if (!text.blanked(owner, Blank.COMMENTS).contains("inheritIO()")) {
            throw new IllegalStateException(
                    "CliOutput no longer calls inheritIO(), so handOffTerminal has stopped being the handoff and this rule exempts a file that does nothing");
        }
        for (String rel : main) {
            if (rel.equals(owner)) continue;
            if (text.blanked(rel, Blank.COMMENTS).contains("inheritIO("))
                v.add(new TextSite(rel, 0, "inheritIO("), rel + " inherits stdio outside the terminal handoff owner");
        }
        v.population(main.size());
    }

    @Guard(
            id = "cli-test-isolated-state",
            why =
                    "the tier's JK_HOME is shared across forks and across runs, so a test that reads the ambient state root — or runs a real engine, which writes it — inherits state instead of establishing it",
            instead = "annotate the test class with @IsolatedState")
    void aCliTestTouchingTheStateRootDeclaresIsolation(Text text, Violations v) {
        List<String> tests = text.files(TESTS);
        if (tests.isEmpty())
            throw new IllegalStateException("no tests under clients/cli/src/test/java, so this verified nothing");
        List<String> accessors = List.of("JkDirs.state()", "JkDirs.builds()");
        boolean sawEngineClientTest = false;
        for (String rel : tests) {
            String code = raw(text, rel);
            if (rel.endsWith("/EngineClientTest.java")) sawEngineClientTest = true;
            boolean isolated = code.lines().anyMatch(l -> l.strip().equals("@IsolatedState"));
            if (!code.contains("@IsolatedState")) {
                for (String accessor : accessors) {
                    if (code.contains(accessor))
                        v.add(new TextSite(rel, 0, accessor), rel + " reads the ambient state root: " + accessor);
                }
            }
            // The annotation on a line of its own, not the word anywhere: prose or a commented-out one must not satisfy
            // the rule.
            if (code.contains("new EngineServer(") && !isolated)
                v.add(
                        new TextSite(rel, 0, "new EngineServer("),
                        rel + " runs an in-process EngineServer, which writes the shared state root");
        }
        if (!sawEngineClientTest)
            throw new IllegalStateException("the scan never reached EngineClientTest, the class this rule exists for");
        v.population(tests.size());
    }

    @Guard(
            id = "ide-client-wiring",
            why =
                    "the IDE clients are wired to jk by string and no compiler looks at them: a palette entry can name a handler that does not exist, a client can shell out to a verb that is not a command, plugin.xml can name a class that is gone",
            instead = "rename on both ends; every arm reads its expectation from the owning file")
    void ideClientsAreWiredToRealThings(Text text, Violations v) {
        // palette entries and registered handlers are the same set
        String packageJson = raw(text, "clients/vscode/package.json");
        String extensionJs = raw(text, "clients/vscode/extension.js");
        TreeSet<String> declared = matches(Pattern.compile("\"command\"\\s*:\\s*\"([^\"]+)\""), packageJson, 1);
        TreeSet<String> registered = matches(Pattern.compile("registerCommand\\(\\s*\"([^\"]+)\""), extensionJs, 1);
        if (declared.isEmpty())
            throw new IllegalStateException("package.json contributes no command at all — this arm is blind");
        for (String d : declared)
            if (!registered.contains(d))
                v.add(
                        new TextSite("clients/vscode/package.json", 0, d),
                        "package.json contributes " + d + ", which extension.js never registers");
        for (String r : registered)
            if (!declared.contains(r))
                v.add(
                        new TextSite("clients/vscode/extension.js", 0, r),
                        "extension.js registers " + r + ", which package.json never contributes");
        // every shelled-out verb is a real jk command name or alias
        TreeSet<String> verbs = new TreeSet<>();
        Pattern name = Pattern.compile("String name\\(\\)\\s*\\{\\s*return \"([a-z][a-z0-9-]*)\";");
        Pattern aliases = Pattern.compile(
                "List<String> aliases\\(\\)\\s*\\{[^}]*?return List\\.of\\(([^)]*)\\);", Pattern.DOTALL);
        Pattern quoted = Pattern.compile("\"([a-z][a-z0-9-]*)\"");
        for (String rel : text.files("clients/cli/src/main/java/cc/jumpkick/command/**/*Command.java")) {
            String code = raw(text, rel);
            verbs.addAll(matches(name, code, 1));
            for (String block : matches(aliases, code, 1)) verbs.addAll(matches(quoted, block, 1));
        }
        if (verbs.size() < 40)
            throw new IllegalStateException("verb owner scan under cc/jumpkick/command found " + verbs.size()
                    + " — the name()/aliases() shape moved and this arm is blind");
        TreeSet<String> shelled = matches(Pattern.compile("runJk\\(\\[\\s*\"([a-z][a-z0-9-]*)\""), extensionJs, 1);
        String cliAction = IDEA_JAVA + "cc/jumpkick/idea/JkCliAction.java";
        if (exists(text, cliAction))
            shelled.addAll(matches(
                    Pattern.compile("super\\(\\s*\"[^\"]*\"\\s*,\\s*\"([a-z][a-z0-9-]*)\""), raw(text, cliAction), 1));
        String syncService = IDEA_JAVA + "cc/jumpkick/idea/JkSyncService.java";
        if (exists(text, syncService))
            shelled.addAll(matches(
                    Pattern.compile("JkCliRunner\\.run\\([^,]+,\\s*List\\.of\\(\"([a-z][a-z0-9-]*)\""),
                    raw(text, syncService),
                    1));
        if (shelled.isEmpty())
            throw new IllegalStateException(
                    "found no jk verbs invoked by either IDE client — the call shape moved and this arm is blind");
        for (String s : shelled)
            if (!verbs.contains(s))
                v.add(
                        new TextSite("clients/vscode/extension.js", 0, "verb " + s),
                        "an IDE client shells out to `" + s + "`, which is not a name or alias of any :cli command");
        // plugin.xml names classes that exist
        String pluginXml = "clients/intellij/src/main/resources/META-INF/plugin.xml";
        TreeSet<String> classes = matches(
                Pattern.compile("(?:class|implementation)=\"(cc\\.jumpkick\\.idea\\.[A-Za-z0-9_.$]+)\""),
                raw(text, pluginXml),
                1);
        if (classes.isEmpty())
            throw new IllegalStateException(
                    "plugin.xml declares no cc.jumpkick.idea implementation class — this arm is blind");
        for (String fqcn : classes) {
            String outer = fqcn.split("\\$", 2)[0];
            String source = IDEA_JAVA + outer.replace('.', '/') + ".java";
            if (!exists(text, source)) {
                v.add(
                        new TextSite(pluginXml, 0, fqcn),
                        "plugin.xml names " + fqcn + " but " + source + " does not exist");
            } else if (fqcn.contains("$")) {
                String inner = fqcn.split("\\$", 2)[1];
                if (!raw(text, source).contains("class " + inner))
                    v.add(
                            new TextSite(pluginXml, 0, fqcn),
                            "plugin.xml names " + fqcn + " but " + source + " declares no class " + inner);
            }
        }
        // every wire field the plugin reads is one the engine emits
        String modelFile = IDEA_JAVA + "cc/jumpkick/idea/JkWireModel.java";
        if (!exists(text, modelFile))
            throw new IllegalStateException("clients/intellij's JkWireModel.java is gone — this arm is blind");
        String model = raw(text, modelFile);
        TreeSet<String> read = matches(
                Pattern.compile("str(?:Field|Array)\\(\\s*(?:body|json)\\s*,\\s*\"([A-Za-z][A-Za-z0-9]*)\""), model, 1);
        read.addAll(matches(Pattern.compile("\\\\\"([A-Za-z][A-Za-z0-9]*)\\\\\"\\s*:"), model, 1));
        if (read.isEmpty())
            throw new IllegalStateException(
                    "JkWireModel reads no recognised wire field — the parse shape moved and this arm is blind");
        String emitted = raw(text, "shared/wire/src/main/java/cc/jumpkick/wire/protocol/IdeWireModel.java");
        for (String field : read)
            if (!emitted.contains("\\\"" + field + "\\\":"))
                v.add(
                        new TextSite(modelFile, 0, field),
                        "JkWireModel reads wire field " + field + ", which IdeWireModel.encode() does not emit");
        // the IntelliJ plugin pins no untilBuild
        Matcher until = Pattern.compile("untilBuild\\s*(?:=|\\.set\\()\\s*\"([^\"]+)\"")
                .matcher(raw(text, "clients/intellij/build.gradle.kts"));
        if (until.find())
            v.add(
                    new TextSite("clients/intellij/build.gradle.kts", 0, "untilBuild"),
                    "clients/intellij pins untilBuild " + until.group(1)
                            + " — the plugin becomes uninstallable the moment JetBrains ships the next build line");
        v.population(declared.size() + verbs.size() + classes.size() + read.size());
    }
}
