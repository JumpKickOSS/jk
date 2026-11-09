// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Windows PowerShell script that runs elevated, behind the one UAC prompt: it adds each root to
 * Defender's folder exclusions as a literal path (no process exclusions), sets {@code
 * LongPathsEnabled} when asked, then checks every root with {@code MpCmdRun -CheckExclusion} (for up
 * to ten seconds, since Defender takes a moment to apply a new exclusion) and
 * writes one tab-separated line per outcome to the result file:
 *
 * <pre>
 * verified  &lt;root&gt;
 * missing   &lt;root&gt;  &lt;why&gt;     (added, but Defender still scans it: a policy wins)
 * refused   &lt;root&gt;  &lt;why&gt;     (a forbidden folder, never added)
 * failed    &lt;root&gt;  &lt;error&gt;
 * longpaths ok|failed  &lt;error&gt;
 * </pre>
 *
 * The {@link Forbidden} rules are part of the script, so a root that reaches it without passing the
 * proposal is refused there too. It travels as {@code -EncodedCommand}, never as a file another
 * process could rewrite before it runs.
 */
public final class ElevatedScript {

    /** The script's longest encoded form a command line holds, with room for the other arguments. */
    static final int MAX_ENCODED = 30_000;

    private ElevatedScript() {}

    /** The script for {@code roots}, each already checked against {@code rules}. */
    public static String render(List<String> roots, boolean longPaths, String resultFile, Forbidden rules) {
        for (String root : roots) {
            String why = rules.reason(root);
            if (why != null) throw new IllegalArgumentException(root + " may not be excluded: " + why);
        }
        return renderUnchecked(roots, longPaths, resultFile, rules);
    }

    /** {@link #render} without the check in this process, so a test can show the script's own refusal. */
    static String renderUnchecked(List<String> roots, boolean longPaths, String resultFile, Forbidden rules) {
        String programs =
                rules.programFolders().stream().map(ElevatedScript::quote).collect(Collectors.joining(","));
        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference = 'Stop'\n");
        s.append("$result = ").append(quote(resultFile)).append('\n');
        s.append("$profileDir = ").append(quote(rules.profile())).append('\n');
        s.append("$jkHome = ").append(quote(rules.home())).append('\n');
        s.append("$systemRoot = ").append(quote(rules.systemRoot())).append('\n');
        s.append("$programs = @(").append(programs).append(")\n");
        s.append("$roots = @(")
                .append(roots.stream().map(ElevatedScript::quote).collect(Collectors.joining(",")))
                .append(")\n");
        s.append("$longPaths = ").append(longPaths ? "$true" : "$false").append('\n');
        String programFiles = rules.programFolders().isEmpty()
                ? "C:\\Program Files"
                : rules.programFolders().getFirst();
        s.append("$mpCmdRun = ")
                .append(quote(WinPaths.join(programFiles, "Windows Defender\\MpCmdRun.exe")))
                .append('\n');
        s.append(GUARD);
        s.append(BODY);
        return s.toString();
    }

    /** {@code script} as {@code powershell.exe -EncodedCommand} takes it: base64 of UTF-16LE. */
    public static String encode(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        if (encoded.length() > MAX_ENCODED) throw new IllegalArgumentException("too many folders for one prompt");
        return encoded;
    }

    /** The arguments {@code powershell.exe} runs {@code script} with. */
    public static String arguments(String script) {
        return "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand "
                + encode(script);
    }

    /** The commands a person runs in an admin PowerShell when no prompt can be shown. */
    public static List<String> manual(List<String> roots, boolean longPaths) {
        List<String> out = new ArrayList<>();
        for (String root : roots) out.add("Add-MpPreference -ExclusionPath " + quote(root));
        if (longPaths) {
            out.add("Set-ItemProperty HKLM:\\SYSTEM\\CurrentControlSet\\Control\\FileSystem LongPathsEnabled 1");
        }
        return out;
    }

    /** A PowerShell single-quoted literal: nothing in it expands. */
    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** The {@link Forbidden} rules in PowerShell. */
    private static final String GUARD = """
            function Get-Key([string]$p) { $k = $p.Replace('/', '\\').ToLowerInvariant().TrimEnd('\\'); if ($k.Length -eq 2) { $k += '\\' }; $k }
            function Test-Under([string]$child, [string]$parent) {
              $c = Get-Key $child; $p = Get-Key $parent
              if ($c -eq $p) { return $true }
              if (-not $p.EndsWith('\\')) { $p += '\\' }
              return $c.StartsWith($p)
            }
            function Get-Refusal([string]$p) {
              if ($p -notmatch '^[A-Za-z]:\\\\[^*?%"<>|]*$') { return 'not an absolute drive path without wildcards' }
              if ((Get-Key $p).Length -le 3) { return 'a drive root' }
              foreach ($q in @($profileDir, $systemRoot)) { if (Test-Under $q $p) { return 'the profile, Windows, or a folder above them' } }
              foreach ($q in $programs) { if (Test-Under $q $p) { return 'a program folder or one above it' } }
              foreach ($n in @('Documents', 'Desktop')) { if ((Get-Key $p) -eq (Get-Key (Join-Path $profileDir $n))) { return "the whole $n" } }
              if (Test-Under $p (Join-Path $profileDir 'Downloads')) { return 'under Downloads' }
              if (Test-Under $p $systemRoot) { return 'under Windows' }
              foreach ($q in $programs) { if (Test-Under $p $q) { return 'under a program folder' } }
              if ((Test-Under $p (Join-Path $profileDir 'AppData')) -and -not (Test-Under $p $jkHome)) { return 'under AppData' }
              return $null
            }
            """;

    private static final String BODY = """
            $out = New-Object System.Collections.Generic.List[string]
            try {
              $added = New-Object System.Collections.Generic.List[string]
              foreach ($r in $roots) {
                $why = Get-Refusal $r
                if ($why) { $out.Add("refused`t$r`t$why"); continue }
                try { Add-MpPreference -ExclusionPath $r -ErrorAction Stop; $added.Add($r) }
                catch { $out.Add("failed`t$r`t" + $_.Exception.Message.Replace("`r", ' ').Replace("`n", ' ')) }
              }
              if ($longPaths) {
                try {
                  Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\FileSystem' -Name LongPathsEnabled -Value 1 -Type DWord
                  $out.Add("longpaths`tok")
                } catch { $out.Add("longpaths`tfailed`t" + $_.Exception.Message.Replace("`r", ' ').Replace("`n", ' ')) }
              }
              foreach ($r in $added) {
                $excluded = $false
                for ($i = 0; $i -lt 20 -and -not $excluded; $i++) {
                  if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
                  & $mpCmdRun -CheckExclusion -path $r | Out-Null
                  $excluded = $LASTEXITCODE -eq 0
                }
                if ($excluded) { $out.Add("verified`t$r") }
                else { $out.Add("missing`t$r`tDefender still scans it; a policy or Tamper Protection may override local exclusions") }
              }
            } catch {
              $out.Add("failed`t`t" + $_.Exception.Message.Replace("`r", ' ').Replace("`n", ' '))
            } finally {
              [System.IO.File]::WriteAllLines($result, $out.ToArray(), (New-Object System.Text.UTF8Encoding $false))
            }
            """;
}
