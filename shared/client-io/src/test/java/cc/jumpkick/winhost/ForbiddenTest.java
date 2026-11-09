// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ForbiddenTest {

    private final Forbidden rules = new Forbidden(
            "C:\\Users\\ada",
            "C:\\Users\\ada\\AppData\\Local\\jk",
            "C:\\Windows",
            List.of("C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData"),
            true);

    @Test
    void refusesRootsProfilesAndSystemFolders() {
        assertThat(rules.reason("C:\\")).isEqualTo("a drive root");
        assertThat(rules.reason("D:")).isNotNull();
        assertThat(rules.reason("C:\\Users\\ada")).contains("profile");
        assertThat(rules.reason("C:\\Users")).contains("profile");
        assertThat(rules.reason("C:\\Users\\ada\\Documents")).isEqualTo("the whole Documents");
        assertThat(rules.reason("C:\\Users\\ada\\Desktop")).isEqualTo("the whole Desktop");
        assertThat(rules.reason("C:\\Users\\ada\\Downloads\\repo")).isEqualTo("under Downloads");
        assertThat(rules.reason("C:\\Windows\\Temp")).isEqualTo("under Windows");
        assertThat(rules.reason("C:\\Program Files\\Git")).isEqualTo("under a program folder");
        assertThat(rules.reason("C:\\Users\\ada\\AppData\\Roaming\\x")).isEqualTo("under AppData");
    }

    @Test
    void refusesAnythingThatIsNotALiteralDrivePath() {
        assertThat(rules.reason("%USERPROFILE%\\src")).isNotNull();
        assertThat(rules.reason("C:\\src\\*")).isNotNull();
        assertThat(rules.reason("\\\\server\\share")).isNotNull();
        assertThat(rules.reason("src")).isNotNull();
    }

    @Test
    void allowsProjectsHomeAndProjectsUnderDocuments() {
        assertThat(rules.reason("C:\\Users\\ada\\IdeaProjects")).isNull();
        assertThat(rules.reason("C:\\Users\\ada\\AppData\\Local\\jk")).isNull();
        assertThat(rules.reason("C:\\Users\\ada\\Documents\\app")).isNull();
        assertThat(rules.reason("D:\\src")).isNull();
    }
}
