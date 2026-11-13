// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.Registry.Hive;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Windows section of {@code jk doctor}: one row per host condition that slows or blocks
 * JumpKick, each read without admin rights. The rows run together and the section answers within
 * {@link #BUDGET}; a row still running then says it was not checked.
 *
 * @param home {@code JK_HOME}
 * @param project the current project's root, or null outside one
 */
public record WindowsHostCheck(
        HostFacts facts,
        SetupMemo memo,
        Path home,
        @Nullable Path project) {

    /** How long the whole section may take. */
    public static final Duration BUDGET = Duration.ofMillis(900);

    static final String DEFENDER_KEY = "SOFTWARE\\Microsoft\\Windows Defender";
    static final String CFA_KEY = DEFENDER_KEY + "\\Windows Defender Exploit Guard\\Controlled Folder Access";
    static final String CFA_POLICY_KEY =
            "SOFTWARE\\Policies\\Microsoft\\Windows Defender\\Windows Defender Exploit Guard\\Controlled Folder Access";
    static final String SAC_KEY = "SYSTEM\\CurrentControlSet\\Control\\CI\\Policy";
    static final String FS_KEY = "SYSTEM\\CurrentControlSet\\Control\\FileSystem";
    static final String ENV_KEY = "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";
    static final String PS_KEY = "SOFTWARE\\Microsoft\\PowerShell\\1\\ShellIds\\Microsoft.PowerShell";
    static final String PS_POLICY_KEY = "SOFTWARE\\Policies\\Microsoft\\Windows\\PowerShell";
    static final String PWSH_POLICY_KEY = "SOFTWARE\\Policies\\Microsoft\\PowerShellCore";

    /** The command that applies the recommended changes. */
    public static final String FIX = "jk doctor --fix";

    /** The command that turns on git's long-path support for this user. */
    static final String GIT_LONG_PATHS_FIX = "git config --global core.longpaths true";

    /** Every row, in the order doctor prints them. */
    public List<HostRow> rows() {
        return rows(BUDGET);
    }

    List<HostRow> rows(Duration budget) {
        List<Supplier<HostRow>> checks = List.of(
                this::smartAppControl,
                this::powerShell,
                this::defender,
                this::folders,
                this::longPaths,
                this::volumes,
                this::arch,
                this::wsl);
        List<String> labels = List.of(
                "smart-app-control:smart app control",
                "powershell:powershell",
                "defender:defender",
                "folders:folders",
                "long-paths:long paths",
                "volumes:volumes",
                "arch:arch",
                "wsl:wsl");
        long deadline = Clock.SYSTEM.nanos() + budget.toNanos();
        List<HostRow> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HostRow>> running = new ArrayList<>();
            for (Supplier<HostRow> check : checks) running.add(pool.submit(check::get));
            for (int i = 0; i < running.size(); i++) {
                String[] idLabel = labels.get(i).split(":", 2);
                out.add(await(running.get(i), deadline, idLabel[0], idLabel[1]));
            }
            pool.shutdownNow();
        }
        return out;
    }

    private static HostRow await(Future<HostRow> row, long deadline, String id, String label) {
        try {
            return row.get(Math.max(1, deadline - Clock.SYSTEM.nanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slow) {
            row.cancel(true);
            return HostRow.ok(id, label, "not checked: took longer than " + BUDGET.toMillis() + " ms");
        } catch (ExecutionException e) {
            return HostRow.ok(id, label, "not checked: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HostRow.ok(id, label, "not checked: interrupted");
        }
    }

    // ---- Smart App Control ----

    HostRow smartAppControl() {
        String id = "smart-app-control", label = "smart app control";
        Integer state = facts.registry().dword(Hive.LOCAL_MACHINE, SAC_KEY, "VerifiedAndReputablePolicyState");
        String fix = "turn it off in Windows Security > App & browser control > Smart App Control;"
                + " it can be turned back on";
        if (state == null || state == 0) return HostRow.ok(id, label, state == null ? "not present" : "off");
        String what = state == 2 ? "in evaluation, and may turn on and block" : "on: it blocks";
        return HostRow.warn(
                id, label, what + " binaries you build (native images, jpackage apps, test fixtures, JNI DLLs)", fix);
    }

    // ---- PowerShell ----

    /** An execution policy and who set it. */
    public record Policy(String name, boolean byGroupPolicy) {
        public boolean loadsProfile() {
            String p = name.toLowerCase(Locale.ROOT);
            return p.equals("remotesigned") || p.equals("unrestricted") || p.equals("bypass");
        }
    }

    HostRow powerShell() {
        String id = "powershell", label = "powershell";
        Policy desktop = desktopPolicy();
        Policy core = corePolicy();
        String detail =
                "a new Windows PowerShell is " + desktop.name() + (core == null ? "" : ", pwsh is " + core.name());
        boolean blocked = !desktop.loadsProfile() || (core != null && !core.loadsProfile());
        if (!blocked) return HostRow.ok(id, label, detail + "; the JumpKick profile block loads");
        boolean policy = (!desktop.loadsProfile() && desktop.byGroupPolicy())
                || (core != null && !core.loadsProfile() && core.byGroupPolicy());
        if (policy) {
            return HostRow.warn(
                    id,
                    label,
                    detail + " by Group Policy, so the JumpKick profile block does not load",
                    "ask your administrator; cmd.exe and the User PATH still find jk");
        }
        return HostRow.warn(
                id,
                label,
                detail + ", so the JumpKick profile block does not load",
                "Set-ExecutionPolicy -Scope CurrentUser RemoteSigned");
    }

    /** The policy a new Windows PowerShell gets. */
    public Policy desktopPolicy() {
        Registry r = facts.registry();
        Policy gp = groupPolicy(r, PS_POLICY_KEY);
        if (gp != null) return gp;
        for (Hive hive : List.of(Hive.CURRENT_USER, Hive.LOCAL_MACHINE)) {
            String p = r.string(hive, PS_KEY, "ExecutionPolicy");
            if (p != null && defined(p)) return new Policy(p, false);
        }
        return new Policy("Restricted", false);
    }

    /** pwsh's policy, or null when pwsh is not installed. */
    @Nullable
    Policy corePolicy() {
        String programFiles = facts.env("ProgramFiles");
        Path pwshHome = programFiles == null ? null : Path.of(WinPaths.join(programFiles, "PowerShell\\7"));
        if (pwshHome == null || !facts.isFile(Path.of(WinPaths.join(pwshHome.toString(), "pwsh.exe")))) return null;
        Policy gp = groupPolicy(facts.registry(), PWSH_POLICY_KEY);
        if (gp != null) return gp;
        List<String> configs = new ArrayList<>();
        for (String docs : documentsFolders()) configs.add(WinPaths.join(docs, "PowerShell\\powershell.config.json"));
        configs.add(WinPaths.join(pwshHome.toString(), "powershell.config.json"));
        for (String config : configs) {
            String p = configPolicy(facts.read(Path.of(config)));
            if (p != null && defined(p)) return new Policy(p, false);
        }
        return new Policy("RemoteSigned", false);
    }

    private static @Nullable Policy groupPolicy(Registry r, String key) {
        for (Hive hive : List.of(Hive.LOCAL_MACHINE, Hive.CURRENT_USER)) {
            Integer enabled = r.dword(hive, key, "EnableScripts");
            if (enabled == null) continue;
            if (enabled == 0) return new Policy("Restricted", true);
            String p = r.string(hive, key, "ExecutionPolicy");
            if (p != null && defined(p)) return new Policy(p, true);
        }
        return null;
    }

    private static final Pattern CONFIG_POLICY =
            Pattern.compile("\"Microsoft\\.PowerShell:ExecutionPolicy\"\\s*:\\s*\"([A-Za-z]+)\"");

    static @Nullable String configPolicy(@Nullable String json) {
        if (json == null) return null;
        Matcher m = CONFIG_POLICY.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static boolean defined(@Nullable String policy) {
        return policy != null && !policy.isBlank() && !policy.equalsIgnoreCase("Undefined");
    }

    // ---- Defender ----

    /** Defender's real-time protection as a standard user can read it. */
    public enum Defender {
        ON,
        REAL_TIME_OFF,
        PASSIVE,
        OFF,
        UNKNOWN
    }

    /** Defender's real-time state as a standard user can read it. */
    public Defender defenderState() {
        Registry r = facts.registry();
        Integer running = r.dword(Hive.LOCAL_MACHINE, DEFENDER_KEY, "IsServiceRunning");
        Integer disabled = r.dword(Hive.LOCAL_MACHINE, DEFENDER_KEY, "DisableAntiVirus");
        Integer passive = r.dword(Hive.LOCAL_MACHINE, DEFENDER_KEY, "PassiveMode");
        Integer rtpOff =
                r.dword(Hive.LOCAL_MACHINE, DEFENDER_KEY + "\\Real-Time Protection", "DisableRealtimeMonitoring");
        Integer rtpPolicyOff = r.dword(
                Hive.LOCAL_MACHINE,
                "SOFTWARE\\Policies\\Microsoft\\Windows Defender\\Real-Time Protection",
                "DisableRealtimeMonitoring");
        if (running == null && disabled == null) return Defender.UNKNOWN;
        if (Integer.valueOf(0).equals(running) || Integer.valueOf(1).equals(disabled)) return Defender.OFF;
        if (Integer.valueOf(1).equals(passive)) return Defender.PASSIVE;
        if (Integer.valueOf(1).equals(rtpOff) || Integer.valueOf(1).equals(rtpPolicyOff)) return Defender.REAL_TIME_OFF;
        return Defender.ON;
    }

    HostRow defender() {
        String id = "defender", label = "defender";
        Defender state = defenderState();
        String folders = home + (project == null ? "" : " and your projects");
        switch (state) {
            case UNKNOWN -> {
                return HostRow.ok(id, label, "could not read Defender's state");
            }
            case OFF, PASSIVE, REAL_TIME_OFF -> {
                String what = state == Defender.REAL_TIME_OFF
                        ? "real-time protection off"
                        : state == Defender.PASSIVE ? "passive" : "off";
                return HostRow.ok(id, label, what + "; if another antivirus is primary, exclude " + folders + " in it");
            }
            default -> {}
        }
        List<String> scanned = new ArrayList<>();
        boolean allVerified = true;
        for (Path folder : checkedFolders()) {
            SetupMemo.Exclusion e = memo.exclusion(folder.toString());
            if (e == SetupMemo.Exclusion.NOT_EXCLUDED) scanned.add(folder.toString());
            if (e != SetupMemo.Exclusion.VERIFIED) allVerified = false;
        }
        if (!scanned.isEmpty()) {
            return HostRow.warn(id, label, "slower: Defender scans " + String.join(" and ", scanned), FIX);
        }
        if (allVerified) {
            String saved = memo.beforeMs() > 0 && memo.afterMs() > 0
                    ? "; build files " + ScanCost.seconds(memo.beforeMs()) + " -> " + ScanCost.seconds(memo.afterMs())
                            + " per 1,000"
                    : "";
            return HostRow.ok(
                    id,
                    label,
                    "real-time on; " + names() + " excluded (checked " + day(memo.checkedAt()) + ")" + saved);
        }
        return HostRow.ok(
                id,
                label,
                "real-time on; its exclusions need admin to read, so they are unchecked; " + FIX + " checks them");
    }

    /** The folders whose exclusion the Defender row reports: {@code JK_HOME} and the project. */
    List<Path> checkedFolders() {
        Set<Path> out = new LinkedHashSet<>();
        out.add(home);
        if (project != null) out.add(project);
        return List.copyOf(out);
    }

    private String names() {
        return project == null ? "JK_HOME" : "JK_HOME and this project";
    }

    private static String day(long millis) {
        return millis <= 0
                ? "never"
                : Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString();
    }

    // ---- folders ----

    HostRow folders() {
        String id = "folders", label = "folders";
        List<String> problems = new ArrayList<>();
        String fix = null;
        for (Path folder : checkedFolders()) {
            for (String drive : oneDrives()) {
                if (WinPaths.under(folder, drive)) {
                    problems.add(folder + " is under OneDrive, which uploads every build output");
                    fix = "move it out of OneDrive, for example to " + WinPaths.join(profile(), "src");
                }
            }
        }
        if (controlledFolderAccess()) {
            for (Path folder : checkedFolders()) {
                for (String guarded : protectedFolders()) {
                    if (WinPaths.under(folder, guarded)) {
                        problems.add("Controlled Folder Access blocks builds writing under " + guarded);
                        if (fix == null) {
                            fix = "move " + folder + " out of " + guarded
                                    + ", or allow jk.exe and java.exe under Ransomware protection";
                        }
                    }
                }
            }
        }
        if (problems.isEmpty()) return HostRow.ok(id, label, "not under OneDrive or a protected folder");
        return HostRow.warn(id, label, String.join("; ", problems), fix);
    }

    private boolean controlledFolderAccess() {
        Registry r = facts.registry();
        Integer policy = r.dword(Hive.LOCAL_MACHINE, CFA_POLICY_KEY, "EnableControlledFolderAccess");
        Integer local = r.dword(Hive.LOCAL_MACHINE, CFA_KEY, "EnableControlledFolderAccess");
        Integer mode = policy != null ? policy : local;
        return mode != null && mode == 1;
    }

    private List<String> oneDrives() {
        Set<String> out = new LinkedHashSet<>();
        for (String name : List.of("OneDrive", "OneDriveCommercial", "OneDriveConsumer")) {
            String v = facts.env(name);
            if (v != null && !v.isBlank()) out.add(v);
        }
        return List.copyOf(out);
    }

    private String profile() {
        String p = facts.env("USERPROFILE");
        return p == null ? "C:\\Users\\you" : p;
    }

    private List<String> documentsFolders() {
        List<String> out = new ArrayList<>();
        out.add(WinPaths.join(profile(), "Documents"));
        for (String drive : oneDrives()) out.add(WinPaths.join(drive, "Documents"));
        return out;
    }

    /** The folders Controlled Folder Access guards by default. */
    private List<String> protectedFolders() {
        List<String> out = new ArrayList<>();
        List<String> names = List.of("Documents", "Pictures", "Videos", "Music", "Desktop", "Favorites");
        for (String name : names) out.add(WinPaths.join(profile(), name));
        for (String drive : oneDrives()) for (String name : names) out.add(WinPaths.join(drive, name));
        return out;
    }

    // ---- long paths ----

    /** Whether Windows' {@code LongPathsEnabled} is set. */
    public boolean longPathsEnabled() {
        Integer windows = facts.registry().dword(Hive.LOCAL_MACHINE, FS_KEY, "LongPathsEnabled");
        return windows != null && windows == 1;
    }

    HostRow longPaths() {
        String id = "long-paths", label = "long paths";
        Boolean git = facts.gitLongPaths();
        boolean windowsOn = longPathsEnabled();
        boolean gitOff = Boolean.FALSE.equals(git);
        List<String> off = new ArrayList<>();
        if (!windowsOn) off.add("Windows limits paths to 260 characters");
        if (gitOff) off.add("git's core.longpaths is off");
        if (off.isEmpty())
            return HostRow.ok(id, label, git == null ? "enabled in Windows" : "enabled in Windows and git");
        List<String> fixes = new ArrayList<>();
        if (!windowsOn) fixes.add(FIX);
        if (gitOff) fixes.add(GIT_LONG_PATHS_FIX);
        return HostRow.warn(id, label, String.join("; ", off), String.join("; ", fixes));
    }

    // ---- volumes ----

    HostRow volumes() {
        String id = "volumes", label = "volumes";
        HostFacts.Volume homeVolume = facts.volume(home);
        if (homeVolume == null) return HostRow.ok(id, label, "could not read the volume of " + home);
        String fs = describe(homeVolume);
        if (project == null) return HostRow.ok(id, label, "JK_HOME on " + fs);
        HostFacts.Volume projectVolume = facts.volume(project);
        if (projectVolume == null) return HostRow.ok(id, label, "JK_HOME on " + fs);
        if (!WinPaths.key(projectVolume.root()).equals(WinPaths.key(homeVolume.root()))) {
            return HostRow.warn(
                    id,
                    label,
                    "this project is on " + projectVolume.root() + " and JK_HOME on " + homeVolume.root()
                            + ", so the cache copies files instead of linking them",
                    "keep projects on the volume that holds JK_HOME, or move JK_HOME to theirs");
        }
        return HostRow.ok(id, label, "this project and JK_HOME share " + fs);
    }

    private static String describe(HostFacts.Volume v) {
        String type = v.type();
        String suffix = type.equalsIgnoreCase("ReFS") ? " (a Dev Drive's trust needs admin to read)" : "";
        return v.root() + " " + type + suffix;
    }

    // ---- WSL ----

    /** The user's WSL distros, which run, and which JumpKick each running one has. Never a warning. */
    HostRow wsl() {
        String id = "wsl", label = "wsl";
        List<WslDistros.Distro> distros = facts.wslDistros();
        if (distros.isEmpty()) return HostRow.ok(id, label, "no WSL distros");
        List<String> parts = new ArrayList<>();
        for (WslDistros.Distro d : distros) {
            List<String> facts = new ArrayList<>();
            if (d.isDefault()) facts.add("default");
            if (d.version() == 1) facts.add("WSL 1");
            facts.add(d.running() ? "running" : "stopped");
            if (d.jumpKick() != null) facts.add(d.jumpKick());
            parts.add(d.name() + " (" + String.join(", ", facts) + ")");
        }
        return HostRow.ok(id, label, String.join("; ", parts));
    }

    // ---- architecture ----

    HostRow arch() {
        String id = "arch", label = "arch";
        String machine = facts.registry().string(Hive.LOCAL_MACHINE, ENV_KEY, "PROCESSOR_ARCHITECTURE");
        boolean armHost = machine != null && machine.equalsIgnoreCase("ARM64");
        String hostName = machine == null ? "this machine" : machine.toUpperCase(Locale.ROOT);
        List<String> emulated = new ArrayList<>();
        if (armHost && x64(facts.processArch())) emulated.add("x64 jk");
        String jdk = facts.jdkArch();
        if (armHost && jdk != null && x64(jdk)) emulated.add("an x64 JDK");
        if (emulated.isEmpty()) return HostRow.ok(id, label, hostName + ", nothing emulated");
        return HostRow.warn(
                id,
                label,
                String.join(" and ", emulated) + " run under ARM64 emulation, several times slower",
                "install the ARM64 build of JumpKick and an ARM64 JDK");
    }

    private static boolean x64(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        return a.equals("amd64") || a.equals("x86_64") || a.equals("x64");
    }
}
