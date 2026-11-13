// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.desktop;

import cc.jumpkick.host.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * JumpKick's toast identity in the Windows profile, registered from a WSL guest through interop, so a
 * WSL engine's toasts read "JumpKick" with the logo even with no Windows install. A Windows install's
 * Start-menu shortcut already carries it and is left alone; otherwise the guest writes the icon under
 * {@code JumpKick} in the user's local application data and a {@code JumpKick.lnk} that opens its
 * dashboard.
 */
public final class WslWindowsDesktop {

    /** Runs Windows and WSL commands from the guest; the seam tests replace. */
    public interface Shell {
        /** Runs {@code argv} to its end and answers what it printed; null when it could not. */
        @Nullable
        String output(List<String> argv);

        /** Runs {@code argv}; true when it exited 0. */
        boolean run(List<String> argv);
    }

    /** The Windows folders the registration needs, one per line. */
    static final String FOLDERS_SCRIPT = PowerShell.PRELUDE
            + "[Environment]::GetFolderPath('ApplicationData')\n"
            + "[Environment]::GetFolderPath('LocalApplicationData')\n"
            + "[Environment]::GetFolderPath('UserProfile')\n"
            + "$env:SystemRoot\n";

    /** Where Windows keeps PowerShell, for a guest whose {@code PATH} lacks the Windows folders. */
    static final String WINDOWS_POWERSHELL = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    private final Shell shell;
    private boolean ensured;
    private @Nullable String powershell;

    public WslWindowsDesktop(Shell shell) {
        this.shell = shell;
    }

    /**
     * The {@code powershell.exe} this guest runs: Windows' own, reached through {@code wslpath}, so it
     * starts whether or not WSL appends the Windows folders to {@code PATH}; else the bare name.
     */
    public synchronized String powershell() {
        String known = powershell;
        if (known != null) return known;
        Path mapped = linux(WINDOWS_POWERSHELL);
        known = mapped != null && Files.isRegularFile(mapped) ? mapped.toString() : "powershell.exe";
        powershell = known;
        return known;
    }

    /** {@code argv} from {@link PowerShell#command}, starting this guest's {@link #powershell}. */
    public List<String> command(List<String> argv) {
        if (argv.isEmpty() || !argv.getFirst().equals("powershell.exe")) return argv;
        List<String> out = new ArrayList<>(argv);
        out.set(0, powershell());
        return List.copyOf(out);
    }

    /**
     * Registers the identity when the Windows profile lacks it, once per process; the shortcut opens
     * {@code dashboard}. Best effort: the toast goes out either way.
     */
    public synchronized void ensure(String dashboard) {
        if (ensured) return;
        ensured = true;
        String out = shell.output(command(PowerShell.command(FOLDERS_SCRIPT)));
        if (out == null) return;
        List<String> folders = out.strip().lines().map(String::strip).toList();
        if (folders.size() < 4 || folders.stream().anyMatch(String::isEmpty)) return;
        String entry =
                folders.get(0) + "\\Microsoft\\Windows\\Start Menu\\Programs\\" + DesktopIdentity.APP_NAME + ".lnk";
        Path entryHere = linux(entry);
        if (entryHere == null || Files.isRegularFile(entryHere)) return;
        String ico = folders.get(1) + "\\" + DesktopIdentity.APP_NAME + "\\jumpkick.ico";
        Path icoHere = linux(ico);
        if (icoHere == null) return;
        try (InputStream in = DesktopIdentity.class.getResourceAsStream("jumpkick.ico")) {
            if (in == null) return;
            Path parent = icoHere.getParent();
            if (parent != null) Files.createDirectories(parent);
            AtomicWrites.replace(icoHere, in.readAllBytes());
        } catch (IOException | RuntimeException unwritable) {
            return;
        }
        String explorer = folders.get(3) + "\\explorer.exe";
        shell.run(command(
                PowerShell.command(DesktopIdentity.shortcutScript(entry, explorer, dashboard, folders.get(2), ico))));
    }

    /** {@code windowsPath} as this guest reaches it, through {@code wslpath}; null when it cannot. */
    private @Nullable Path linux(String windowsPath) {
        String out = shell.output(List.of("wslpath", "-u", windowsPath));
        return out == null || out.isBlank() ? null : Path.of(out.strip());
    }
}
