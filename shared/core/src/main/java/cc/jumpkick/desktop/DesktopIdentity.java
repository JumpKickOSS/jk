// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.desktop;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The name and icon JumpKick's desktop notifications carry, and their registration with the host.
 * On Windows that is a Start-menu shortcut, {@code JumpKick.lnk}, whose {@code
 * System.AppUserModel.ID} is {@link #AUMID} and whose icon is {@code <home>/lib/desktop/jumpkick.ico}:
 * a toast shown under that AppUserModelID reads "JumpKick" with the logo. On Linux and other
 * freedesktop hosts it is the desktop entry {@code jumpkick.desktop} under {@code $XDG_DATA_HOME/applications}, naming {@code
 * <home>/lib/desktop/jumpkick.png}. macOS has nothing to register. Both entries open the dashboard
 * ({@code jk web}).
 *
 * <p>Only the default home ({@code ~/.jk}) registers itself without being asked: the installers do
 * it, and {@link #ensure} does it before the first notification when the registration is missing.
 */
public final class DesktopIdentity {

    /** The name notifications show as their sender. */
    public static final String APP_NAME = "JumpKick";

    /** The AppUserModelID Windows toasts are shown under, carried by the Start-menu shortcut. */
    public static final String AUMID = "cc.jumpkick.JumpKick";

    /** The desktop entry's id: {@code jumpkick.desktop}, the {@code desktop-entry} hint's value. */
    public static final String DESKTOP_ENTRY = APP_NAME.toLowerCase(Locale.ROOT);

    private static final String COMMENT = "Open the JumpKick dashboard";

    private static final long RUN_TIMEOUT_SECONDS = 60;

    /** Runs a command to its end; the seam tests replace. */
    @FunctionalInterface
    public interface Runner {
        /** Runs {@code argv}; true when it exited 0. */
        boolean run(List<String> argv);
    }

    private final String os;
    private final JkDirs dirs;
    private final Path userHome;
    private final Function<String, @Nullable String> env;
    private final Runner runner;
    private boolean ensured;

    /**
     * The identity of {@code dirs}' home on host {@code os}; {@code userHome} and {@code env} place
     * the shortcut and the desktop entry.
     */
    public DesktopIdentity(
            String os, JkDirs dirs, Path userHome, Function<String, @Nullable String> env, Runner runner) {
        this.os = Objects.requireNonNull(os);
        this.dirs = Objects.requireNonNull(dirs);
        this.userHome = Objects.requireNonNull(userHome);
        this.env = Objects.requireNonNull(env);
        this.runner = Objects.requireNonNull(runner);
    }

    /** This host and home, running real processes. */
    public static DesktopIdentity current() {
        return new DesktopIdentity(
                Os.name(),
                JkDirs.current(),
                Path.of(System.getProperty("user.home")),
                JkDirs::env,
                DesktopIdentity::runProcess);
    }

    /** The PNG icon: the Linux notification's and desktop entry's. */
    public Path png() {
        return iconDir().resolve("jumpkick.png");
    }

    /** The ICO icon: the Windows shortcut's. */
    public Path ico() {
        return iconDir().resolve("jumpkick.ico");
    }

    /** The registration this host keeps: the shortcut on Windows, null on macOS, else the desktop entry. */
    public @Nullable Path entry() {
        if (Os.isWindows(os)) {
            String appData = env.apply("APPDATA");
            Path roaming = appData == null || appData.isBlank()
                    ? userHome.resolve("AppData").resolve("Roaming")
                    : Path.of(appData);
            return roaming.resolve("Microsoft")
                    .resolve("Windows")
                    .resolve("Start Menu")
                    .resolve("Programs")
                    .resolve(APP_NAME + ".lnk");
        }
        if (Os.isDarwin(os)) return null;
        String data = env.apply("XDG_DATA_HOME");
        Path root = data == null || data.isBlank() || !Path.of(data).isAbsolute()
                ? userHome.resolve(".local").resolve("share")
                : Path.of(data);
        return root.resolve("applications").resolve(DESKTOP_ENTRY + ".desktop");
    }

    /** True when this host has nothing to register or its registration and icon are in place. */
    public boolean registered() {
        Path entry = entry();
        if (entry == null) return true;
        return Files.isRegularFile(entry) && Files.isRegularFile(Os.isWindows(os) ? ico() : png());
    }

    /** Writes the icon and the registration, replacing what is there; nothing on a host with none. */
    public void register() throws IOException {
        Path entry = entry();
        if (entry == null) return;
        Files.createDirectories(Objects.requireNonNull(entry.getParent()));
        if (Os.isWindows(os)) {
            writeIcon("jumpkick.ico", ico());
            if (!runner.run(PowerShell.command(shortcutScript(entry)))) {
                throw new IOException("could not write the Start-menu shortcut " + entry);
            }
        } else {
            writeIcon("jumpkick.png", png());
            replace(entry, desktopEntry().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * What {@link #unregister} would delete that exists: the registration when this is the default
     * home, the only one that registers itself, and this home's icon directory.
     */
    public List<Path> installed() {
        List<Path> out = new ArrayList<>();
        Path entry = entry();
        if (entry != null && dirs.isDefaultHome() && Files.exists(entry)) out.add(entry);
        if (Files.exists(iconDir())) out.add(iconDir());
        return out;
    }

    /** Deletes the registration and the icons; true when there was something to delete. */
    public boolean unregister() throws IOException {
        Path entry = entry();
        boolean removed = entry != null && Files.deleteIfExists(entry);
        removed |= Files.deleteIfExists(png());
        removed |= Files.deleteIfExists(ico());
        Files.deleteIfExists(iconDir());
        return removed;
    }

    /**
     * Registers the default home when its registration is missing, once per process. Best effort:
     * a notification goes out either way.
     */
    public synchronized void ensure() {
        if (ensured) return;
        ensured = true;
        if (registered() || !dirs.isDefaultHome()) return;
        try {
            register();
        } catch (IOException | RuntimeException ignored) {
            // The notification still shows, without the name or the icon.
        }
    }

    private Path iconDir() {
        return dirs.productLibDir().resolve("desktop");
    }

    /** The client the entries start: the native binary, else the JVM client's launcher. */
    private Path client() {
        Path bin = dirs.binDirectory();
        if (!Os.isWindows(os)) return bin.resolve("jk");
        Path exe = bin.resolve("jk.exe");
        Path bat = bin.resolve("jk.bat");
        return Files.isRegularFile(exe) || !Files.isRegularFile(bat) ? exe : bat;
    }

    /** The {@code jumpkick.desktop} text: hidden from menus, there for the notification's name and icon. */
    String desktopEntry() {
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + APP_NAME + "\n"
                + "Comment=" + COMMENT + "\n"
                + "Exec=" + execQuote(client().toString()) + " web\n"
                + "Icon=" + png() + "\n"
                + "Terminal=false\n"
                + "NoDisplay=true\n"
                + "Categories=Development;\n";
    }

    /** {@code arg} quoted for a desktop entry's {@code Exec} key. */
    private static String execQuote(String arg) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : arg.toCharArray()) {
            if (c == '"' || c == '`' || c == '$' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.append('"').toString().replace("%", "%%");
    }

    /**
     * The PowerShell that writes the shortcut through {@code IShellLinkW} and sets its AppUserModelID
     * through {@code IPropertyStore}, which no scriptable COM object exposes.
     */
    String shortcutScript(Path entry) {
        return PowerShell.PRELUDE
                + "Add-Type -TypeDefinition " + PowerShell.literal(SHORTCUT_CS) + "\n"
                + "[JumpKickDesktop.Shortcut]::Save(" + PowerShell.literal(entry.toString())
                + ", " + PowerShell.literal(client().toString())
                + ", " + PowerShell.literal("web")
                + ", " + PowerShell.literal(userHome.toString())
                + ", " + PowerShell.literal(ico().toString())
                + ", " + PowerShell.literal(COMMENT)
                + ", " + PowerShell.literal(AUMID) + ")\n";
    }

    private static final String SHORTCUT_CS = """
            using System;
            using System.Runtime.InteropServices;
            using System.Runtime.InteropServices.ComTypes;
            using System.Text;

            namespace JumpKickDesktop {
              [ComImport, Guid("000214F9-0000-0000-C000-000000000046"),
               InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IShellLinkW {
                void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder file, int cch, IntPtr data, uint flags);
                void GetIDList(out IntPtr idl);
                void SetIDList(IntPtr idl);
                void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder name, int cch);
                void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
                void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder dir, int cch);
                void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
                void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int cch);
                void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
                void GetHotkey(out short hotkey);
                void SetHotkey(short hotkey);
                void GetShowCmd(out int show);
                void SetShowCmd(int show);
                void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int cch, out int index);
                void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
                void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
                void Resolve(IntPtr hwnd, uint flags);
                void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
              }

              [StructLayout(LayoutKind.Sequential, Pack = 4)]
              struct PropertyKey {
                public Guid FormatId;
                public uint PropertyId;
              }

              [StructLayout(LayoutKind.Explicit, Size = 24)]
              struct PropVariant {
                [FieldOffset(0)] public ushort Type;
                [FieldOffset(8)] public IntPtr Value;
              }

              [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"),
               InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
              interface IPropertyStore {
                void GetCount(out uint count);
                void GetAt(uint index, out PropertyKey key);
                void GetValue(ref PropertyKey key, out PropVariant value);
                void SetValue(ref PropertyKey key, ref PropVariant value);
                void Commit();
              }

              public static class Shortcut {
                const ushort VT_LPWSTR = 31;
                const int SW_SHOWMINNOACTIVE = 7;

                public static void Save(string lnk, string target, string args, string dir, string icon,
                                        string description, string aumid) {
                  Type type = Type.GetTypeFromCLSID(new Guid("00021401-0000-0000-C000-000000000046"));
                  IShellLinkW link = (IShellLinkW) Activator.CreateInstance(type);
                  link.SetPath(target);
                  link.SetArguments(args);
                  link.SetWorkingDirectory(dir);
                  link.SetIconLocation(icon, 0);
                  link.SetDescription(description);
                  link.SetShowCmd(SW_SHOWMINNOACTIVE);
                  PropertyKey key = new PropertyKey();
                  key.FormatId = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");
                  key.PropertyId = 5;
                  PropVariant value = new PropVariant();
                  value.Type = VT_LPWSTR;
                  value.Value = Marshal.StringToCoTaskMemUni(aumid);
                  try {
                    IPropertyStore store = (IPropertyStore) link;
                    store.SetValue(ref key, ref value);
                    store.Commit();
                  } finally {
                    Marshal.FreeCoTaskMem(value.Value);
                  }
                  ((IPersistFile) link).Save(lnk, true);
                  Marshal.ReleaseComObject(link);
                }
              }
            }
            """;

    private void writeIcon(String resource, Path target) throws IOException {
        try (InputStream in = DesktopIdentity.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("this build carries no " + resource);
            Files.createDirectories(iconDir());
            replace(target, in.readAllBytes());
        }
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean runProcess(List<String> argv) {
        Process p;
        try {
            p = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        try {
            if (p.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return p.exitValue() == 0;
            p.destroyForcibly();
            return false;
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
