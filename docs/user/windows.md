# Windows setup

JumpKick runs on a stock Windows 11 machine: one NTFS system drive, Microsoft Defender on, and
Smart App Control on. This page sets that machine up for fast builds, in order of payoff. A Dev
Drive comes last: it is the best setup, and the one you can skip.

Install first, from PowerShell (Windows PowerShell 5.1 or PowerShell 7):

```powershell
irm https://jumpkick.build/install.ps1 | iex
```

The installer puts `jk` on your User PATH, so cmd, PowerShell and every program you start find it,
and adds JumpKick's hook to your PowerShell profiles. When a new PowerShell would refuse to load a
profile (the `Restricted` policy client Windows ships with), it prints the one command that
allows it and changes nothing itself. Details are in [Install](install.md).

## 1. Smart App Control

Smart App Control (SAC) blocks every executable and DLL that is not signed by a trusted publisher,
including the ones you build yourself: native images, `jpackage` apps, JNI libraries and test
fixtures, not only `jk.exe`. A clean Windows 11 install has it on or in evaluation mode.

See its state:

```powershell
(Get-MpComputerStatus).SmartAppControlState    # On, Eval or Off
```

`jk doctor` reports it too, as its `smart app control` row.

The `jk.exe` of every release after 0.51.1 is Authenticode-signed, and SAC runs it:
`Get-AuthenticodeSignature (Get-Command jk).Source` shows `Status` `Valid` and the signer. An older
release's `jk.exe`, or a `target\dist\jk.exe` you built yourself, is not; for those, choose one:
- install the [JVM client](install.md#the-jvm-client), which SAC lets run, with
  `$env:JK_CLIENT = "jvm"; irm https://jumpkick.build/install.ps1 | iex`;
- or turn SAC off in Windows Security > App & browser control > Smart App Control settings. Since
  the April 2026 update (KB5083769) it can be turned back on without reinstalling Windows.

## 2. `jk doctor`, then `jk doctor --fix`

```powershell
jk doctor
jk doctor --fix
```

`jk doctor`'s `windows` section names each host condition that slows or blocks JumpKick, read as a
standard user with no UAC prompt: the rows are listed in
[Install: Windows host checks](install.md#windows-host-checks). `jk doctor --fix` puts the fixes to
you as one decision, the same one a coding agent raises through the MCP `doctor` tool with
`fix=true`, and the same one offered once after your first successful `jk build` at a terminal:

- **Defender exclusions** for `JK_HOME` and the folders where you keep code. Defender scans every
  file a build writes and reads back; on NTFS that scan is the largest cost a build pays that it
  does not need to. Approving shows one UAC prompt. The decision shows what the scan costs on your
  machine, measured before and after.
- **The PowerShell policy** the profile hook needs (`RemoteSigned` for your user), when a new
  PowerShell would refuse it.
- **Long paths** need no setting: every git command JumpKick runs enables git's long-path support for
  itself, and the JDK handles long paths on its own.

Each change has its own checkbox, and declining one declines nothing else.

To do the same by hand, from an elevated PowerShell, one literal path per folder (Defender does not
follow junctions, and an environment variable in an exclusion is read as SYSTEM's):

```powershell
Add-MpPreference -ExclusionPath "C:\Users\you\.jk", "C:\Users\you\src"
```

Do not turn off Memory Integrity or real-time protection, and do not exclude `java.exe` as a
process: those widen what malware can do on the machine far more than a folder exclusion does.

## 3. Where to keep code

- One project root, such as `%USERPROFILE%\src`, excluded once, holds every project.
- Not under a OneDrive-synced folder (Documents, Desktop): OneDrive uploads every build output and
  can hold files open while a build replaces them. `jk doctor`'s `folders` row says when a project
  is there.
- If you use Controlled Folder Access, keep code outside the folders it guards, or allow `java.exe`
  and `jk.exe` there; the same `folders` row names a guarded project.
- Keep the project on the same volume as `JK_HOME`. JumpKick hardlinks build inputs and outputs out
  of its store, and a hard link cannot cross volumes, so a project on another volume gets copies
  (`jk doctor`'s `volumes` row, and one line in the engine log per volume pair).

## 4. What JumpKick does for you

- The engine and its workers run at full speed when the window is in the background: Windows'
  power throttling (EcoQoS) is turned off for them.
- A command typed at the desktop and one run over ssh (or by a service or a scheduled task) are
  served by two engines. An engine and everything it runs keep the logon of whoever started it, and
  an ssh logon cannot reach your credential vault, so docker's and git's credential helpers would
  fail in an engine an ssh session started. `jk engine status` names the kind in its `Logon` row
  ([Engine § Several engines](engine.md#several-engines)).
- Every git command JumpKick runs has long paths on.
- Defender's cost is measured, not guessed: the `defender` row and the `jk doctor --fix` decision
  carry the number for your machine.

## 5. Optional, best: a Dev Drive

A Dev Drive is a ReFS volume that Defender scans asynchronously in performance mode; Microsoft
measures builds about 25% faster on one than on NTFS with Defender on. It needs:
- Windows 11, an administrator, and 50 GB or more;
- a second disk, or free space to shrink from C: (Settings > System > Storage > Advanced storage
  settings > Disks & volumes > Create Dev Drive). It cannot be the system drive.

Put `JK_HOME` and your projects on the Dev Drive together, so the store and the projects stay on one
volume and hard links keep working. Set `JK_HOME` as a User environment variable before installing;
the installer then treats that home as your install (User PATH, engine, notifications), as it
does `%USERPROFILE%\.jk`:

```powershell
[Environment]::SetEnvironmentVariable("JK_HOME", "D:\jk", "User")
$env:JK_HOME = "D:\jk"
irm https://jumpkick.build/install.ps1 | iex
```

A `JK_HOME` set in one shell alone (`$env:JK_HOME = ...` without the first line) stays a private
home: a scratch install the User PATH never names.

Then trust the drive, so Defender's performance mode applies (elevated):

```powershell
fsutil devdrv trust D:
fsutil devdrv query D:
```

## 6. Managed machines

On a machine your IT department manages, these are what to ask for:
- **Defender exclusions** for `JK_HOME` and your project root, by literal path, if Group Policy or
  Intune does not let you add them.
- **AppLocker or WDAC**: an allow rule for `%USERPROFILE%\.jk\bin\jk.exe` and the JDKs JumpKick
  installs under `%USERPROFILE%\.jk\jdks`, or use the JVM client on an already allowed JDK.
- **Third-party antivirus**: the same folder exclusions; every scanner taxes build I/O as Defender
  does.
- **Execution policy**: JumpKick needs none; the installer runs as `irm | iex`.

## 7. WSL

JumpKick runs inside a WSL distro as on any Linux: install it there with the Linux installer. Keep
the code on the distro's own filesystem (`~/src`), not under `/mnt/c`: every file access across
that boundary goes through a network file system and is many times slower. A WSL distro runs an
engine of its own with its own dashboard ([Engine](engine.md)); `jk doctor` on Windows lists your
distros in its `wsl` row.
