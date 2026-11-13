// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.Registry.Hive;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowsHostCheckTest {

    private static final Path HOME = Path.of("C:\\Users\\ada\\.jk");
    private static final Path APP = Path.of("C:\\Users\\ada\\src\\app");

    private final FakeHost host = new FakeHost();

    private WindowsHostCheck check(SetupMemo memo) {
        return new WindowsHostCheck(host, memo, HOME, APP);
    }

    private WindowsHostCheck check() {
        return check(SetupMemo.NONE);
    }

    @Test
    void aHealthyHostIsAllOk() {
        List<HostRow> rows = check().rows();
        assertThat(rows)
                .extracting(HostRow::id)
                .containsExactly(
                        "smart-app-control",
                        "powershell",
                        "defender",
                        "folders",
                        "long-paths",
                        "volumes",
                        "arch",
                        "wsl");
        assertThat(rows).allMatch(r -> r.status() == HostRow.Status.OK);
    }

    @Test
    void smartAppControlOnOrInEvaluationWarnsWithTheSettingToTurnItOff() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.SAC_KEY, "VerifiedAndReputablePolicyState", 1);
        HostRow on = check().smartAppControl();
        assertThat(on.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(on.detail()).startsWith("on: it blocks binaries you build").contains("native images");
        assertThat(on.fix()).contains("Smart App Control").contains("turned back on");

        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.SAC_KEY, "VerifiedAndReputablePolicyState", 2);
        assertThat(check().smartAppControl().detail()).startsWith("in evaluation");
    }

    @Test
    void aRestrictedPolicyKeepsTheProfileBlockOutAndNamesTheCurrentUserFix() {
        host.strings.clear();
        HostRow row = check().powerShell();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("Windows PowerShell is Restricted").contains("does not load");
        assertThat(row.fix()).isEqualTo("Set-ExecutionPolicy -Scope CurrentUser RemoteSigned");
    }

    @Test
    void aGroupPolicyRestrictionNamesTheAdministrator() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.PS_POLICY_KEY, "EnableScripts", 1)
                .string(Hive.LOCAL_MACHINE, WindowsHostCheck.PS_POLICY_KEY, "ExecutionPolicy", "AllSigned");
        HostRow row = check().powerShell();
        assertThat(row.detail()).contains("AllSigned by Group Policy");
        assertThat(row.fix()).startsWith("ask your administrator");
    }

    @Test
    void pwshIsReadFromItsConfigFileAndDefaultsToRemoteSigned() {
        host.existing.add(WinPaths.key("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
        assertThat(check().powerShell().detail()).contains("pwsh is RemoteSigned");
        host.files.put(
                WinPaths.key("C:\\Users\\ada\\Documents\\PowerShell\\powershell.config.json"),
                "{ \"Microsoft.PowerShell:ExecutionPolicy\": \"Restricted\" }");
        HostRow row = check().powerShell();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("pwsh is Restricted");
    }

    @Test
    void defenderWithUncheckedExclusionsIsAHintNotAWarning() {
        HostRow row = check().defender();
        assertThat(row.status()).isEqualTo(HostRow.Status.OK);
        assertThat(row.detail()).contains("real-time on").contains("need admin");
    }

    @Test
    void verifiedExclusionsAreOkWithTheDayTheyWereChecked() {
        SetupMemo memo = new SetupMemo(
                SetupMemo.Answer.ACCEPTED,
                1,
                1_762_646_400_000L,
                List.of("C:\\Users\\ada\\.jk", "C:\\Users\\ada\\src"),
                List.of(),
                List.of(),
                Map.of(),
                0,
                0);
        HostRow row = check(memo).defender();
        assertThat(row.status()).isEqualTo(HostRow.Status.OK);
        assertThat(row.detail()).contains("JK_HOME and this project excluded (checked 2025-11-09)");
        SetupMemo measured = new SetupMemo(
                memo.answer(), 1, memo.checkedAt(), memo.verified(), List.of(), List.of(), Map.of(), 1_900, 400);
        assertThat(check(measured).defender().detail()).endsWith("; build files 1.9 s -> 400 ms per 1,000");
    }

    @Test
    void aDeclinedSetupIsOneQuietSlowerLine() {
        SetupMemo memo =
                new SetupMemo(SetupMemo.Answer.DECLINED, 1, 0, List.of(), List.of(), List.of(), Map.of(), 0, 0);
        HostRow row = check(memo).defender();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).startsWith("slower: Defender scans C:\\Users\\ada\\.jk and C:\\Users\\ada\\src\\app");
    }

    @Test
    void defenderOffOrPassiveAsksForTheExclusionInTheOtherAntivirus() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "IsServiceRunning", 0);
        assertThat(check().defenderState()).isEqualTo(WindowsHostCheck.Defender.OFF);
        assertThat(check().defender().detail()).startsWith("off; if another antivirus is primary");

        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "IsServiceRunning", 1)
                .dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "PassiveMode", 1);
        assertThat(check().defenderState()).isEqualTo(WindowsHostCheck.Defender.PASSIVE);

        host.dwords.remove(Hive.LOCAL_MACHINE + "|" + WindowsHostCheck.DEFENDER_KEY + "|PassiveMode");
        host.dword(
                Hive.LOCAL_MACHINE,
                WindowsHostCheck.DEFENDER_KEY + "\\Real-Time Protection",
                "DisableRealtimeMonitoring",
                1);
        assertThat(check().defenderState()).isEqualTo(WindowsHostCheck.Defender.REAL_TIME_OFF);
    }

    @Test
    void aProjectUnderOneDriveWarns() {
        host.env.put("OneDrive", "C:\\Users\\ada\\OneDrive");
        WindowsHostCheck c = new WindowsHostCheck(host, SetupMemo.NONE, HOME, Path.of("C:\\Users\\ada\\OneDrive\\app"));
        HostRow row = c.folders();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("under OneDrive");
        assertThat(row.fix()).contains("C:\\Users\\ada\\src");
    }

    @Test
    void controlledFolderAccessWarnsOnlyForAProjectUnderAProtectedFolder() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.CFA_KEY, "EnableControlledFolderAccess", 1);
        assertThat(check().folders().status()).isEqualTo(HostRow.Status.OK);
        Path docs = Path.of("C:\\Users\\ada\\Documents\\app");
        HostRow row = new WindowsHostCheck(host, SetupMemo.NONE, HOME, docs).folders();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("Controlled Folder Access").contains("Documents");
    }

    @Test
    void longPathsOffInWindowsOrGitNamesBothFixes() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.FS_KEY, "LongPathsEnabled", 0);
        host.git = false;
        HostRow row = check().longPaths();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("260 characters").contains("core.longpaths is off");
        assertThat(row.fix()).contains("jk doctor --fix").contains("git config --global core.longpaths true");

        host.git = null;
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.FS_KEY, "LongPathsEnabled", 1);
        assertThat(check().longPaths().detail()).isEqualTo("enabled in Windows");
    }

    @Test
    void projectAndHomeOnDifferentVolumesWarnThatTheCacheCopies() {
        host.volumes.put(WinPaths.key(APP), new HostFacts.Volume("D:\\", "ReFS"));
        HostRow row = check().volumes();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("D:\\").contains("copies files instead of linking");

        host.volumes.put(WinPaths.key(HOME), new HostFacts.Volume("D:\\", "ReFS"));
        assertThat(check().volumes().detail()).contains("ReFS").contains("Dev Drive");
    }

    @Test
    void x64UnderArm64EmulationWarns() {
        host.string(Hive.LOCAL_MACHINE, WindowsHostCheck.ENV_KEY, "PROCESSOR_ARCHITECTURE", "ARM64");
        HostRow row = check().arch();
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("x64 jk and an x64 JDK run under ARM64 emulation");

        host.arch = "aarch64";
        host.jdk = "aarch64";
        assertThat(check().arch().status()).isEqualTo(HostRow.Status.OK);
    }

    @Test
    void aSlowProbeIsReportedUncheckedWithinTheBudget() {
        host.delayMs = 400;
        long start = System.nanoTime();
        List<HostRow> rows = check().rows(Duration.ofMillis(150));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(600));
        assertThat(rows).anyMatch(r -> r.detail().startsWith("not checked"));
    }
}
