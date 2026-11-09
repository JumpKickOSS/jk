// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupMemoTest {

    @Test
    void roundTripsEveryField(@TempDir Path state) throws Exception {
        SetupMemo memo = new SetupMemo(
                SetupMemo.Answer.ACCEPTED,
                10,
                20,
                List.of("C:\\Users\\ada\\.jk", "C:\\Users\\ada\\IdeaProjects"),
                List.of("D:\\src"),
                List.of("C:\\Users\\ada\\source\\repos"),
                Map.of("E:\\work", "refused by Group Policy"),
                1900,
                400);
        Path file = SetupMemo.file(state);
        memo.write(file);
        assertThat(SetupMemo.read(file)).isEqualTo(memo);
    }

    @Test
    void aMissingOrUnreadableFileIsNone(@TempDir Path state) throws Exception {
        assertThat(SetupMemo.read(SetupMemo.file(state))).isEqualTo(SetupMemo.NONE);
        Files.writeString(SetupMemo.file(state), "answer = [broken");
        assertThat(SetupMemo.read(SetupMemo.file(state)).answer()).isNull();
    }

    @Test
    void exclusionFollowsVerifiedScannedAndDeclinedRoots() {
        SetupMemo memo = new SetupMemo(
                SetupMemo.Answer.ACCEPTED,
                1,
                1,
                List.of("C:\\Users\\ada\\src"),
                List.of("D:\\work"),
                List.of("C:\\Users\\ada\\repos"),
                Map.of(),
                0,
                0);
        assertThat(memo.exclusion("c:/users/ada/src/app")).isEqualTo(SetupMemo.Exclusion.VERIFIED);
        assertThat(memo.exclusion("D:\\work\\x")).isEqualTo(SetupMemo.Exclusion.NOT_EXCLUDED);
        assertThat(memo.exclusion("C:\\Users\\ada\\repos")).isEqualTo(SetupMemo.Exclusion.NOT_EXCLUDED);
        assertThat(memo.exclusion("C:\\Users\\ada\\srcx")).isEqualTo(SetupMemo.Exclusion.UNKNOWN);
    }
}
