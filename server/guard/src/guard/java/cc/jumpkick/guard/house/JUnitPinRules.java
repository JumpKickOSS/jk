// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.OwnerMissing;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One JUnit version across every fixture manifest, and it is the one jk itself resolved.
 *
 * <p>Test fixtures that scaffold a project pin the JUnit train exactly — {@code version = "6.1.3"}
 * rather than {@code latest} — and that pin is deliberate: {@code KotlinSerializationTest} records
 * why, and a dozen fixtures point at it. Owning {@code [test-dependencies]} keeps the injected
 * {@code junit-jupiter} {@code latest} out of the graph, so the lock a test writes is deterministic;
 * a half-published release (metadata advertising a version whose POM still 404s, seen live with
 * 6.1.2) cannot then decide what a fixture resolves.
 *
 * <p>What the pins must not do is <em>drift apart</em>. They sat at 6.1.1 while jk's own lock had
 * moved to 6.1.3, so the tree was testing against a JUnit nobody shipped against. Every pin equals
 * the version {@code jk-lock.toml} resolved, so a deliberate {@code jk lock} bump carries the
 * fixtures with it and a hand-edited pin fails here instead of quietly diverging.
 *
 * <p>Not in scope: coordinate strings used as arbitrary fixture data — mock-repo seeds, parser and
 * editor tests, solver fixtures — whose version is a label rather than a pin. Nor a pin that is the
 * point of its fixture: a manifest line that provokes a version conflict says so with a trailing
 * {@code # off the train:} comment, and the guard leaves that one pin alone.
 *
 * <p>A guard and not the JUnit class {@code JUnitPinParityTest} it was: that class walked the whole
 * checkout (over 100,000 entries, {@code node_modules} included) from a fast-tier test JVM while the
 * build running it wrote the same volume, the disease that timed out {@code WireKeyClosureTest}
 * and {@code LockRowParserTest}. Here the walk is the guard's text view, and
 * the check is keyed to the text it reads.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class JUnitPinRules {

    /** This guard's own fixture trees, which pin a wrong version on purpose. */
    private static final String OWN_FIXTURES = "server/guard/fixtures/junit-pin-parity/";

    /** The JUnit train's coordinates, as a fixture manifest spells them. */
    private static final Pattern PIN = Pattern.compile(
            "name = \"junit-(?:jupiter|platform-launcher|vintage-engine)\", version = \"=?([0-9][^\"]*)\"");

    /** A pin the fixture wants wrong, said on its own line: {@code … }  # off the train: why}. */
    private static final String OFF_THE_TRAIN = "# off the train:";

    /** {@code org.junit.*} artifacts in the lock, with the version each resolved to. */
    private static final Pattern LOCKED = Pattern.compile(
            "name\\s*=\\s*\"(org\\.junit\\.[^:\"]+:[^:\"]+):jar:\"\\s*\\n\\s*version\\s*=\\s*\"([^\"]+)\"");

    @Guard(
            id = "junit-pin-parity",
            why =
                    "a fixture manifest pinning the JUnit train at a version jk-lock.toml did not resolve tests"
                            + " the tree against a JUnit nobody ships against, and a reader cannot tell which number is current",
            instead = "bump the lock deliberately and carry the fixture pins with it; a pin that is its fixture's"
                    + " point says so with a trailing '# off the train: why'")
    @Fixture("server/guard/fixtures/junit-pin-parity")
    void junitPinParity(Text text, Violations v) {
        List<String> lockFiles = text.files("jk-lock.toml");
        if (lockFiles.isEmpty()) throw new OwnerMissing("no jk-lock.toml at the root: it records what jk resolved");
        String lockText = text.blanked(lockFiles.getFirst(), Blank.NONE);
        Set<String> lockedVersions = new TreeSet<>();
        int lockedCount = 0;
        Matcher lm = LOCKED.matcher(lockText);
        while (lm.find()) {
            lockedVersions.add(lm.group(2));
            lockedCount++;
        }
        // A regex that has stopped seeing the lock would otherwise report parity over an empty set.
        // The train is seven artifacts today; require most of them.
        if (lockedCount < 5)
            throw new OwnerMissing("read " + lockedCount + " org.junit.* artifacts from jk-lock.toml, expected the"
                    + " train's seven: the lock pattern no longer sees the lock");
        if (lockedVersions.size() != 1)
            throw new OwnerMissing(
                    "the JUnit train resolves to " + lockedVersions + " in jk-lock.toml, not one version");
        String expected = lockedVersions.iterator().next();

        List<String> sources = new ArrayList<>();
        for (String glob : List.of("**/src/test/**/*.java", "**/src/integrationTest/**/*.java"))
            for (String rel : text.files(glob)) if (!rel.startsWith(OWN_FIXTURES)) sources.add(rel);
        int pins = 0;
        for (String rel : sources) {
            String src = text.blanked(rel, Blank.NONE);
            if (!src.contains("name = \"junit-")) continue;
            Matcher m = PIN.matcher(src);
            while (m.find()) {
                if (restOfLine(src, m.end()).contains(OFF_THE_TRAIN)) continue;
                pins++;
                if (!expected.equals(m.group(1)))
                    v.add(
                            new TextSite(rel, lineOf(src, m.start()), m.group()),
                            "pins JUnit " + m.group(1) + ", not " + expected + ", the version jk-lock.toml resolved");
            }
        }
        if (pins == 0)
            throw new OwnerMissing("no fixture manifest pins the JUnit train: the pin pattern no longer sees them");
        v.population(pins);
    }

    private static String restOfLine(String src, int from) {
        int nl = src.indexOf('\n', from);
        return nl < 0 ? src.substring(from) : src.substring(from, nl);
    }

    private static int lineOf(String body, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (body.charAt(i) == '\n') line++;
        return line;
    }
}
