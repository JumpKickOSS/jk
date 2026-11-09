// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElevatedScriptTest {

    private final Forbidden rules =
            new Forbidden("C:\\Users\\ada", "C:\\Users\\ada\\.jk", "C:\\Windows", List.of("C:\\Program Files"), true);

    @Test
    void addsEachRootAsALiteralAndChecksItAfterwards() {
        String s = ElevatedScript.render(
                List.of("C:\\Users\\ada\\.jk", "D:\\it's here"), true, "C:\\Users\\ada\\.jk\\state\\r.txt", rules);
        assertThat(s).contains("$roots = @('C:\\Users\\ada\\.jk','D:\\it''s here')");
        assertThat(s).contains("Add-MpPreference -ExclusionPath $r");
        assertThat(s).doesNotContain("ExclusionProcess");
        assertThat(s).contains("$longPaths = $true").contains("LongPathsEnabled -Value 1");
        assertThat(s).contains("-CheckExclusion -path $r");
        assertThat(s.indexOf("Get-Refusal $r")).isLessThan(s.indexOf("Add-MpPreference"));
        assertThat(s).contains("$mpCmdRun = 'C:\\Program Files\\Windows Defender\\MpCmdRun.exe'");
        assertThat(s).contains("} finally {\n  [System.IO.File]::WriteAllLines($result");
    }

    @Test
    void refusesAForbiddenRootBeforeItIsRendered() {
        assertThatThrownBy(() -> ElevatedScript.render(List.of("C:\\"), false, "r.txt", rules))
                .hasMessageContaining("a drive root");
    }

    @Test
    void anInjectedRootStillMeetsTheScriptsOwnGuard() {
        String s = ElevatedScript.renderUnchecked(
                List.of("C:\\", "C:\\Users\\ada", "C:\\Users\\ada\\Downloads"), false, "r.txt", rules);
        assertThat(s).contains("$profileDir = 'C:\\Users\\ada'");
        assertThat(s).contains("return 'a drive root'").contains("return 'under Downloads'");
        assertThat(s).contains("if ($why) { $out.Add(\"refused`t$r`t$why\"); continue }");
    }

    @Test
    void encodesAsUtf16Base64AndRefusesACommandLineTooLong() {
        String encoded = ElevatedScript.encode("Write-Output 'é'");
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE))
                .isEqualTo("Write-Output 'é'");
        assertThatThrownBy(() -> ElevatedScript.encode("x".repeat(20_000))).hasMessageContaining("too many");
        assertThat(ElevatedScript.arguments("x")).startsWith("-NoProfile -NonInteractive -ExecutionPolicy Bypass");
    }

    @Test
    void manualCommandsQuoteEachRoot() {
        assertThat(ElevatedScript.manual(List.of("D:\\it's"), true))
                .containsExactly(
                        "Add-MpPreference -ExclusionPath 'D:\\it''s'",
                        "Set-ItemProperty HKLM:\\SYSTEM\\CurrentControlSet\\Control\\FileSystem LongPathsEnabled 1");
    }
}
