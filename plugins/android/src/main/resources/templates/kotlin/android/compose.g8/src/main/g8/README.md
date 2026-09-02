# $name$

A minimal Jetpack Compose app built by [JumpKick](https://jumpkick.cc) — no Gradle, no AGP.

```bash
jk lock          # resolve + pin dependencies (first run provisions the Android SDK if needed)
jk build         # aapt2 + kotlinc (Compose compiler) + d8 → target/$name$-debug.apk
jk test          # JVM unit tests (no device needed)
```

## Run on a device or emulator

`jk run` builds the debug APK, installs it over `adb`, and starts the launcher activity:

```bash
jk run           # build + adb install + am start
jk dev           # rebuild and redeploy on change
```

An emulator can be created and launched with `jk avd`. Instrumented (on-device) tests are
`jk instrument`'s territory — this starter ships JVM unit tests only.

## Where things are

| Path | What |
|---|---|
| `src/main/AndroidManifest.xml` | Launcher activity, label, theme |
| `src/main/kotlin/` | `MainActivity` (Compose UI) + `Counter` (plain state holder) |
| `src/test/kotlin/` | JVM unit tests `jk test` runs without a device |
| `jk.toml` | `[android]` table (`compose = true` adds the Compose compiler), Compose BOM |
