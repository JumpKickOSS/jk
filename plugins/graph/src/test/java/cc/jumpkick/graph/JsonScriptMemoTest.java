// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * A script's facts are read once per content: jhipster-sample-app's operation.model.ts (at
 * 6b000b5d23a36c45e01472471b84a44fa2464044, Apache-2.0, verbatim) is read on the first refresh, not
 * on the next while its content hash is the same, and again once it changes; contents no file has
 * any more are dropped.
 */
class JsonScriptMemoTest {

    static final String MODEL = "src/main/webapp/app/entities/operation/operation.model.ts";

    @Test
    void aScriptIsReadOncePerContent() throws IOException {
        String text = Files.readString(Path.of("src/test/resources/json-anchors/jhipster/operation.model.ts.txt"));
        List<String> read = new ArrayList<>();
        String ws = "test:" + getClass().getName();
        String sha = "sha-1";

        JsonScriptMemo first = new JsonScriptMemo(ws, Map.of(MODEL, sha));
        JsonScriptMemo.Facts facts = Objects.requireNonNull(first.of(MODEL, rel -> {
            read.add(rel);
            return text;
        }));
        assertThat(facts.generated()).isFalse();
        assertThat(facts.types())
                .extracting(m -> m.type() + "." + m.name() + ":" + m.line())
                .contains("IOperation.id:7", "IOperation.description:9", "IOperation.amount:10");

        JsonScriptMemo second = new JsonScriptMemo(ws, Map.of(MODEL, sha));
        assertThat(second.of(MODEL, rel -> {
                    read.add(rel);
                    return text;
                }))
                .isEqualTo(facts);
        assertThat(read).as("the same content is not read again").containsExactly(MODEL);

        JsonScriptMemo changed = new JsonScriptMemo(ws, Map.of(MODEL, sha + "-2"));
        changed.of(MODEL, rel -> {
            read.add(rel);
            return text.replace("amount?:", "total?:");
        });
        assertThat(read).hasSize(2);
        changed.retain();
        new JsonScriptMemo(ws, Map.of(MODEL, sha)).of(MODEL, rel -> {
            read.add(rel);
            return text;
        });
        assertThat(read)
                .as("a content no file had at the last refresh was dropped")
                .hasSize(3);
    }

    @Test
    void withNoHashesEveryAskReads() throws IOException {
        String text = Files.readString(Path.of("src/test/resources/json-anchors/jhipster/operation.model.ts.txt"));
        List<String> read = new ArrayList<>();
        JsonScriptMemo.NONE.of(MODEL, rel -> {
            read.add(rel);
            return text;
        });
        JsonScriptMemo.NONE.of(MODEL, rel -> {
            read.add(rel);
            return text;
        });
        assertThat(read).hasSize(2);
    }

    /**
     * What a requesting file reads is kept the same way: jhipster's bank-account.cy.ts (verbatim) is
     * read and scanned once for two refreshes of one content, its uses and blocks those of a fresh read.
     */
    @Test
    void aRequestingFilesReadsAreKeptPerContent() throws IOException {
        String cy = "src/test/javascript/cypress/e2e/entity/bank-account.cy.ts";
        String text = Files.readString(Path.of("src/test/resources/json-anchors/jhipster/bank-account.cy.ts.txt"));
        String ws = "test:" + getClass().getName() + ":reads";
        List<String> read = new ArrayList<>();
        JsonScriptMemo.Reads first = Objects.requireNonNull(new JsonScriptMemo(ws, Map.of(cy, "sha-cy"))
                .reads(
                        cy,
                        rel -> {
                            read.add(rel);
                            return text;
                        },
                        JsonScriptMemo::read));
        JsonScriptMemo.Reads again = new JsonScriptMemo(ws, Map.of(cy, "sha-cy"))
                .reads(
                        cy,
                        rel -> {
                            read.add(rel);
                            return text;
                        },
                        JsonScriptMemo::read);
        assertThat(again).isSameAs(first);
        assertThat(read).containsExactly(cy);
        JsonScriptMemo.Reads fresh = JsonScriptMemo.read(cy, text);
        assertThat(first.uses()).isEqualTo(fresh.uses());
        assertThat(first.uses()).extracting(JsonReaders.Use::name).contains("name", "balance");
        assertThat(Objects.requireNonNull(first.blocks()).open())
                .isEqualTo(Objects.requireNonNull(fresh.blocks()).open());
    }
}
