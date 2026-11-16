// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One rename shape for every kind (JK-MXDGC), shown on a method: the bound edits applied, the uses
 * the compiler did not bind returned as graded candidates with ids, {@code also} applying chosen ones
 * in a later call, and the proof after the compile settling or undoing the edit.
 */
class RenameShapeTest {

    private static final String DIR = "src/main/java/a/";

    private static RenameFixture ledger(Path dir) throws IOException {
        return new RenameFixture(
                dir,
                RenameFixture.files(
                        DIR + "Ledger.java",
                        "package a;\n\npublic class Ledger {\n    public void post(int amount) {}\n}\n",
                        DIR + "Billing.java",
                        "package a;\n\nclass Billing {\n    void charge(Ledger l) {\n        l.post(3);\n    }\n}\n",
                        "src/main/groovy/a/Report.groovy",
                        "package a\n\nclass Report {\n    def run(ledger) {\n        ledger.post(1)\n    }\n}\n"));
    }

    @Test
    void aGroovyUseIsACandidateAndAlsoAppliesItInALaterCall(@TempDir Path dir) throws IOException {
        RenameFixture ws = ledger(dir);
        String answer = ws.rename("Ledger.post", "record");
        assertThat(answer)
                .startsWith("Renamed Ledger.post to record: 1 declaration, 1 call, in 2 files; each one javac or the"
                        + " compiled classes bound.")
                .contains("Not applied, and should change too (inferred); apply with also=c1:")
                .contains("c1 inferred, dynamic Groovy, bound by name at run time: src/main/groovy/a/Report.groovy:5 |"
                        + " ledger.post(1)");
        assertThat(ws.read("src/main/groovy/a/Report.groovy")).contains("ledger.post(1)");

        String applied = ws.rename("Ledger.post", "record", "also=c1");
        assertThat(applied).startsWith("Applied c1: 1 change in 1 file.").contains("src/main/groovy/a/Report.groovy:5");
        assertThat(ws.read("src/main/groovy/a/Report.groovy")).contains("ledger.record(1)");

        assertThat(ws.rename("Ledger.post", "record", "also=c1"))
                .as("a line already changed is skipped and named, never edited twice")
                .contains("Skipped, edited since the rename");
    }

    @Test
    void anIdTheLastRenameDidNotReturnIsRefused(@TempDir Path dir) throws IOException {
        RenameFixture ws = ledger(dir);
        ws.rename("Ledger.post", "record");
        assertThatThrownBy(() -> ws.rename("Ledger.post", "record", "also=c9"))
                .hasMessageContaining("also names c9, which the last rename did not return; it returned c1");
    }

    @Test
    void theProofSettlesAProvenRenameAndForgetsItsUndoRecord(@TempDir Path dir) throws IOException {
        RenameFixture ws = ledger(dir);
        ws.rename("Ledger.post", "record");
        assertThat(ws.check())
                .isEqualTo("Proven: it compiles, and Ledger.record has the 2 references Ledger.post had, at the same"
                        + " lines; nothing else binds to the new name.");
        assertThat(Files.exists(dir.resolve("target/graph/rename/undo"))).isFalse();
        assertThat(ws.check()).as("nothing left to prove").isEmpty();
    }

    @Test
    void aFailedCompileUndoesEveryFileTheRenameWrote(@TempDir Path dir) throws IOException {
        RenameFixture ws = ledger(dir);
        ws.rename("Ledger.post", "record");
        Files.writeString(
                dir.resolve(DIR + "Billing.java"), ws.read(DIR + "Billing.java").replace("record", "post"));
        assertThat(ws.check())
                .startsWith(
                        "Rolled back, nothing is changed (2 files restored): after the edit the code does not compile");
        assertThat(ws.read(DIR + "Ledger.java")).contains("public void post(int amount)");
        assertThat(ws.read(DIR + "Billing.java")).contains("l.post(3);");
    }
}
