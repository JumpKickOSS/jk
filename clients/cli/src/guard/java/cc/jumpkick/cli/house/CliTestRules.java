// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * House rules over the CLI's tests, which live in two modules: {@code clients/cli}, the suite that
 * talks to the wire and to stubs, and {@code e2e/cli}, the suite that drives a real engine. A
 * workspace suite, so an edit in either module re-runs it. An implausibly small scan throws, so a
 * blind arm is {@code scanner-failed}, never clean.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class CliTestRules {

    private static final List<String> TEST_TREES =
            List.of("clients/cli/src/test/java/**/*.java", "e2e/cli/src/test/java/**/*.java");

    /** Every test source of both modules; throws when the scan is too small to have read them. */
    private static List<String> tests(Text text) {
        List<String> tests = new ArrayList<>();
        for (String glob : TEST_TREES) tests.addAll(text.files(glob));
        if (tests.size() < 400)
            throw new IllegalStateException(
                    "scan of the CLI's test trees: " + tests.size() + " files; measured against 530");
        return tests;
    }

    private static String raw(Text text, String path) {
        return text.blanked(path, Blank.NONE);
    }

    @Guard(
            id = "cli-test-isolated-state",
            why =
                    "the tier's JK_HOME is shared across forks and across runs, so a test that reads the ambient state root — or runs a real engine, which writes it — inherits state instead of establishing it",
            instead = "annotate the test class with @IsolatedState")
    void aCliTestTouchingTheStateRootDeclaresIsolation(Text text, Violations v) {
        List<String> tests = tests(text);
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
            id = "cli-test-shared-store-trees",
            why =
                    "a sandbox store's central, google and jumpkick trees are links into the pool every test sandbox on the host shares, so a test that writes or deletes there reaches every worktree's suites",
            instead = "annotate the test (or its class) with @IsolatedStore")
    void aCliTestTouchingThePooledTreesDeclaresAPrivateStore(Text text, Violations v) {
        List<String> tests = tests(text);
        List<String> ambient =
                List.of("JkStores.store()", "JkDirs.store()", "JkStores.resolve(", "JkStores.storeCas()");
        Pattern tree = Pattern.compile(
                "\"(?:repos/)?(?:central|google|jumpkick)[\"/]|RepositorySpec\\.(?:CENTRAL|GOOGLE|JUMPKICK_NAME)\\b");
        boolean sawLibrarySearch = false;
        for (String rel : tests) {
            String code = raw(text, rel);
            if (rel.endsWith("/LibrarySearchCommandTest.java")) sawLibrarySearch = true;
            if (ambient.stream().noneMatch(code::contains)) continue;
            Matcher m = tree.matcher(code);
            if (!m.find()) continue;
            if (code.lines().anyMatch(l -> l.strip().equals("@IsolatedStore"))) continue;
            v.add(
                    new TextSite(rel, 0, m.group()),
                    rel + " names the ambient store and a pooled tree (" + m.group() + ") without @IsolatedStore");
        }
        if (!sawLibrarySearch)
            throw new IllegalStateException(
                    "the scan never reached LibrarySearchCommandTest, a class that seeds Central's tree");
        v.population(tests.size());
    }
}
