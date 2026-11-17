// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rename's answer as an agent gets it: in its token budget without hiding an id it may pass to
 * {@code also} (the MCP card takes no budget, so a cut id cost a shell call or a second rename), and
 * the same call made again lists what the first left to apply instead of refusing the old name.
 */
class RenameAnswerTest {

    private static final Pattern ID = Pattern.compile("\\bc(\\d+)(?:-c(\\d+))?\\b");

    @Test
    void anAnswerOverItsBudgetSummarisesTheListsByKindWithEveryIdAndARepeatListsEachLine(@TempDir Path dir)
            throws IOException {
        String full =
                new RenameFixture(dir.resolve("full"), RenameFieldTest.petclinic()).rename("Owner.telephone", "phone");
        RenameFixture ws = new RenameFixture(dir.resolve("short"), RenameFieldTest.petclinic());
        String answer = ws.rename(150, "Owner.telephone", "phone");

        assertThat(full.length())
                .as("the fixture's answer is over the small budget")
                .isGreaterThan(150 * 3);
        assertThat(answer)
                .startsWith("Renamed Owner.telephone (field, with getTelephone, setTelephone) to phone:")
                .doesNotContain("TRUNCATED", "Owner.java:", " | ")
                .containsPattern("\n  c\\d+[-,c\\d]* inferred, template bean path: \\d+ in [^\n]*ownerDetails.html")
                .containsPattern("\n  c\\d+[-,c\\d]* inferred, SQL column: \\d+ in [^\n]*schema.sql")
                .contains("h1 hazard: Owner is a JPA entity")
                .endsWith("Each one with its line: repeat this rename (same address and to).");
        assertThat(ids(answer)).as("every id the full answer gives").isEqualTo(ids(full));

        assertThat(ws.check()).startsWith("Proven");
        String again = ws.rename(150, "Owner.telephone", "phone");
        assertThat(again)
                .startsWith("Already renamed Owner.telephone to phone; this call changed nothing.\n")
                .doesNotContain("No method", "Test next");
        for (String line :
                full.lines().filter(l -> l.matches("  c\\d+.*|h\\d+ hazard.*")).toList()) {
            assertThat(again).as("the repeat lists each one with its line").contains(line);
        }
    }

    @Test
    void aRepeatAfterAnAlsoSaysWhatWasApplied(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, RenameFieldTest.petclinic());
        ws.rename("Owner.telephone", "phone");
        ws.check();
        ws.rename("Owner.telephone", "phone", "also=h1");
        ws.check();
        assertThat(ws.rename("Owner.telephone", "phone"))
                .startsWith("Already renamed Owner.telephone to phone; this call changed nothing. Applied since: h1.\n")
                .contains("Judgment, left as they are unless chosen");
    }

    @Test
    void aRenameThatLeftNothingListedSaysSoWhenRepeated(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(
                dir,
                RenameFixture.files(
                        "src/main/java/org/acme/Counter.java",
                        "package org.acme;\n\npublic class Counter {\n    int total() {\n        return 1;\n    }\n"
                                + "\n    int twice() {\n        return total() * 2;\n    }\n}\n"));
        assertThat(ws.rename("org.acme.Counter.total", "sum")).startsWith("Renamed");
        assertThat(ws.check()).startsWith("Proven");
        assertThat(ws.rename("org.acme.Counter.total", "sum"))
                .startsWith("Already renamed org.acme.Counter.total to sum; this call changed nothing.\n")
                .endsWith("\nIt listed nothing to apply.")
                .doesNotContain("No method");
    }

    /** A field written as javadoc writes it (cursor's composer did) is the field; an unknown member says what is taken. */
    @Test
    void aFieldWrittenTypeHashFieldIsRenamedAndAnUnknownMemberSaysWhatARenameTakes(@TempDir Path dir)
            throws IOException {
        RenameFixture ws = new RenameFixture(dir, RenameFieldTest.petclinic());
        assertThat(ws.rename("org.acme.petclinic.owner.Owner#telephone", "phone"))
                .startsWith("Renamed Owner.telephone (field, with getTelephone, setTelephone) to phone:");
        assertThat(ws.read(RenameFieldTest.OWNER_DIR + "Owner.java")).contains("private String phone;");
        assertThat(ws.check()).startsWith("Proven");

        assertThat(ws.rename("Owner.mobile", "cell"))
                .startsWith("No method or field 'Owner.mobile' in the compiled classes.")
                .contains("Pass Type.method, Type.field (or Type#field), Type or a.pkg.");
        assertThat(ws.rename("Ownr", "Person"))
                .startsWith("No type, package, method or field 'Ownr'. Pass Type.method, Type.field");
    }

    /** The ids an answer names, ranges expanded: {@code c3-c5} is c3, c4, c5. */
    static Set<Integer> ids(String answer) {
        Set<Integer> out = new TreeSet<>();
        Matcher m = ID.matcher(answer);
        while (m.find()) {
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
            for (int i = from; i <= to; i++) out.add(i);
        }
        return out;
    }
}
