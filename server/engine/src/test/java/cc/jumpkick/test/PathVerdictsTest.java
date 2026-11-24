// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathVerdictsTest {

    /**
     * A path recorded again is judged once: its verdict is the one {@link ClassReads#classify} gives,
     * with the class a class tree's file loads, kept for the next read, and the suite remembers at
     * most {@link ClassReads#VERDICTS_KEPT} of them.
     */
    @Test
    void a_path_read_again_is_judged_once_within_the_bound(@TempDir Path ws) {
        Path tree = ws.resolve("m/target/test-classes");
        ClassReads.Layout layout = new ClassReads.Layout(List.of(tree), List.of(), Set.of(), ws, null);
        PathVerdicts verdicts = new PathVerdicts(ws, layout);

        PathVerdicts.Verdict loaded = Objects.requireNonNull(verdicts.of("m/target/test-classes/demo/Helper.class"));
        assertThat(loaded.path()).isEqualTo(tree.resolve("demo/Helper.class"));
        assertThat(loaded.where()).isEqualTo(ClassReads.Where.LOADED);
        assertThat(loaded.className()).isEqualTo("demo.Helper");
        assertThat(verdicts.of("m/target/test-classes/demo/Helper.class")).isSameAs(loaded);

        PathVerdicts.Verdict data =
                Objects.requireNonNull(verdicts.of(ws.resolve("in.txt").toString()));
        assertThat(data.where()).isEqualTo(ClassReads.classify(data.path(), layout));
        assertThat(data.className()).isNull();

        for (int i = 0; i < ClassReads.VERDICTS_KEPT; i++) verdicts.of("f" + i + ".txt");
        assertThat(verdicts.of("m/target/test-classes/demo/Helper.class"))
                .as("past the bound the eldest is judged anew")
                .isNotSameAs(loaded)
                .isEqualTo(loaded);
    }
}
