// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The Windows Credential Manager, through Windows PowerShell calling {@code CredReadW}, {@code
 * CredWriteW} and {@code CredDeleteW}. An item is a generic credential whose target is {@code
 * jk:<item>}; its blob is the secret in UTF-16, the way the Credential Manager UI shows one.
 *
 * <p>Windows ships no command that reads a generic credential back ({@code cmdkey} only lists), so
 * the call is a short script. It goes in as {@code -EncodedCommand}, so nothing in it needs
 * quoting; the secret comes in on stdin, and goes out on stdout. The script exits 3 when no
 * credential matches and 1, with the Win32 error on stderr, when the call fails.
 */
public final class WindowsCredentialBackend implements SecretBackend {

    static final String TOOL = "powershell.exe";

    static final int NOT_FOUND = 3;

    /** Windows PowerShell compiles the interop type on every run, which takes about a second. */
    private static final Duration CAP = Duration.ofSeconds(60);

    private static final Duration PROBE_CAP = Duration.ofSeconds(15);

    private final SecretCommand command;

    public WindowsCredentialBackend(SecretCommand command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    @Override
    public String id() {
        return "windows-credential-manager";
    }

    @Override
    public String displayName() {
        return "Windows Credential Manager";
    }

    @Override
    public boolean daemonReachable() {
        return true;
    }

    @Override
    public Probe probe() {
        try {
            SecretCommand.Result r = command.run(argv("exit 0"), null, PROBE_CAP);
            return r.exit() == 0 ? Probe.AVAILABLE : Probe.unavailable(r.complaint());
        } catch (IOException e) {
            return Probe.unavailable(TOOL + " is not available: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> read(String item) {
        SecretCommand.Result r = run(script(item, READ), null);
        if (r.exit() == 0) return Optional.of(r.stdout());
        if (r.exit() == NOT_FOUND) return Optional.empty();
        throw new SecretBackendException(
                "the Windows Credential Manager refused to read " + item + ": " + r.complaint());
    }

    @Override
    public void write(String item, String label, String secret) {
        SecretCommand.Result r = run(script(item, WRITE), secret.getBytes(StandardCharsets.UTF_8));
        if (r.exit() != 0) {
            throw new SecretBackendException(
                    "the Windows Credential Manager refused to store " + item + ": " + r.complaint());
        }
    }

    @Override
    public void delete(String item) {
        SecretCommand.Result r = run(script(item, DELETE), null);
        if (r.exit() == 0 || r.exit() == NOT_FOUND) return;
        throw new SecretBackendException(
                "the Windows Credential Manager refused to remove " + item + ": " + r.complaint());
    }

    /** The credential's target name. */
    static String target(String item) {
        return SecretItems.SERVICE + ":" + SecretItems.requireValid(item);
    }

    /** {@code powershell.exe} running {@code script}, encoded so nothing in it is parsed by a shell. */
    static List<String> argv(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(TOOL, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    /** The whole script for one operation on {@code item}. */
    static String script(String item, String operation) {
        return PREAMBLE + "$target = '" + target(item) + "'\n" + operation;
    }

    private static final String INTEROP = """
            using System;
            using System.Runtime.InteropServices;
            using System.Runtime.InteropServices.ComTypes;
            using System.Text;
            public static class JkCred {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                struct CREDENTIAL {
                    public int Flags; public int Type; public IntPtr TargetName; public IntPtr Comment;
                    public FILETIME LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob;
                    public int Persist; public int AttributeCount; public IntPtr Attributes;
                    public IntPtr TargetAlias; public IntPtr UserName;
                }
                [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern bool CredRead(string target, int type, int flags, out IntPtr cred);
                [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern bool CredWrite(ref CREDENTIAL cred, int flags);
                [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern bool CredDelete(string target, int type, int flags);
                [DllImport("advapi32.dll")]
                static extern void CredFree(IntPtr cred);
                public static int Read(string target, out string secret) {
                    secret = null;
                    IntPtr p;
                    if (!CredRead(target, 1, 0, out p)) return Marshal.GetLastWin32Error();
                    try {
                        CREDENTIAL c = (CREDENTIAL) Marshal.PtrToStructure(p, typeof(CREDENTIAL));
                        secret = Marshal.PtrToStringUni(c.CredentialBlob, c.CredentialBlobSize / 2);
                        return 0;
                    } finally {
                        CredFree(p);
                    }
                }
                public static int Write(string target, string secret) {
                    byte[] blob = Encoding.Unicode.GetBytes(secret);
                    CREDENTIAL c = new CREDENTIAL();
                    c.Type = 1;
                    c.Persist = 2;
                    c.TargetName = Marshal.StringToCoTaskMemUni(target);
                    c.UserName = Marshal.StringToCoTaskMemUni("jk");
                    c.CredentialBlobSize = blob.Length;
                    c.CredentialBlob = Marshal.AllocCoTaskMem(Math.Max(blob.Length, 1));
                    try {
                        Marshal.Copy(blob, 0, c.CredentialBlob, blob.Length);
                        return CredWrite(ref c, 0) ? 0 : Marshal.GetLastWin32Error();
                    } finally {
                        Marshal.FreeCoTaskMem(c.TargetName);
                        Marshal.FreeCoTaskMem(c.UserName);
                        Marshal.FreeCoTaskMem(c.CredentialBlob);
                    }
                }
                public static int Delete(string target) {
                    return CredDelete(target, 1, 0) ? 0 : Marshal.GetLastWin32Error();
                }
            }
            """;

    private static final String PREAMBLE = "$ErrorActionPreference = 'Stop'\n"
            + "[Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false\n"
            + "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false\n"
            + "Add-Type -TypeDefinition @'\n" + INTEROP + "'@\n";

    /** {@code ERROR_NOT_FOUND}, which every one of the three calls returns for an absent target. */
    private static final String FAIL = "if ($e -eq 1168) { exit 3 }\n"
            + "if ($e -ne 0) { [Console]::Error.WriteLine(\"Win32 error $e\"); exit 1 }\n";

    static final String READ =
            "$s = $null\n$e = [JkCred]::Read($target, [ref]$s)\n" + FAIL + "[Console]::Out.Write($s)\nexit 0\n";

    static final String WRITE = "$e = [JkCred]::Write($target, [Console]::In.ReadToEnd())\n" + FAIL + "exit 0\n";

    static final String DELETE = "$e = [JkCred]::Delete($target)\n" + FAIL + "exit 0\n";

    private SecretCommand.Result run(String script, byte @Nullable [] stdin) {
        try {
            return command.run(argv(script), stdin, CAP);
        } catch (IOException e) {
            throw new SecretBackendException(TOOL + " failed: " + e.getMessage(), e);
        }
    }
}
