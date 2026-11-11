// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class GraphSnapshotTest {

    private static final GraphSnapshot.Key KEY = new GraphSnapshot.Key("x", 0, "c", "i", null);

    /** Where the string-table count sits: magic, format, then the key's fields. */
    private static final int STRING_COUNT_AT = 4 + 4 + (2 + 1) + 4 + (2 + 1) + (2 + 1) + 2;

    private static byte[] emptySnapshot() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GraphSnapshot.write(CodeGraph.merge(List.of(Fragment.EMPTY)), KEY, out);
        return out.toByteArray();
    }

    @Test
    void an_intact_snapshot_reads_back() throws Exception {
        GraphSnapshot.Loaded loaded =
                Objects.requireNonNull(GraphSnapshot.read(new ByteArrayInputStream(emptySnapshot())));
        assertThat(loaded.key()).isEqualTo(KEY);
    }

    @Test
    void a_corrupted_table_count_reads_as_no_snapshot() throws Exception {
        byte[] bytes = emptySnapshot();
        ByteBuffer.wrap(bytes).putInt(STRING_COUNT_AT, Integer.MAX_VALUE);
        assertThat(GraphSnapshot.read(new ByteArrayInputStream(bytes))).isNull();
    }

    @Test
    void a_corrupted_string_length_reads_as_no_snapshot() throws Exception {
        byte[] bytes = emptySnapshot();
        ByteBuffer.wrap(bytes).putInt(STRING_COUNT_AT, 1).putInt(STRING_COUNT_AT + 4, Integer.MAX_VALUE - 8);
        assertThat(GraphSnapshot.read(new ByteArrayInputStream(bytes))).isNull();
    }
}
