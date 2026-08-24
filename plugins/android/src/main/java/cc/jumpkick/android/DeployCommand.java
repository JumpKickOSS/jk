// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.build.PluginCommandExec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@code deploy} — {@code adb install -r} the built APK and {@code am start} its launcher.
 * adb from platform-tools; {@code --adb} overrides (test seam).
 */
final class DeployCommand {

    private DeployCommand() {}

    static int run(PluginCommandExec exec) throws Exception {
        Path artifact = exec.mainArtifact().orElse(null);
        if (artifact == null
                || !(artifact.toString().endsWith(".apk") || artifact.toString().endsWith(".aab"))) {
            exec.out("jk run: no APK/AAB built yet — run `jk build` first");
            return 1;
        }
        // A release AAB deploys locally through bundletool: build-apks --mode universal against
        // the debug identity (local testing — Play signs the real install artifacts), then the
        // extracted universal.apk installs like any APK.
        Path apk = artifact.toString().endsWith(".aab") ? universalApk(exec, artifact) : artifact;
        Path adb = adbPath(exec);
        String namespace = exec.config().string("namespace");
        String activity = launcherActivity(AndroidDeps.androidFile(exec.moduleDir(), "AndroidManifest.xml"), namespace);

        exec.label("adb install");
        exec.out("Installing " + apk.getFileName() + " …");
        int install = adb(exec, adb, "install", "-r", apk.toAbsolutePath().toString());
        if (install != 0) return install;

        exec.label("am start");
        exec.out("Launching " + namespace + "/" + activity + " …");
        return adb(exec, adb, "shell", "am", "start", "-n", namespace + "/" + activity);
    }

    /** bundletool build-apks --mode universal over the AAB; the extracted universal.apk. */
    private static Path universalApk(PluginCommandExec exec, Path aab) throws Exception {
        Path bundletool = exec.requireExtra("bundletool");
        Path work = Files.createTempDirectory("jk-deploy-");
        Path apks = work.resolve("universal.apks");
        // The Maven bundletool library embeds no aapt2 — hand it the plugin's own; local-deploy
        // installs sign with the SAME stable debug identity the apk/aab packagers use, so a
        // universal APK from a rebuilt bundle still updates the installed app in place.
        Path aapt2 = AndroidDeps.extractAapt2(exec.requireExtra("aapt2"), work.resolve("tools"));
        Path keystore = DebugKeystore.ensure(DebugKeystore.stableDir(), Path.of(System.getProperty("java.home")));
        exec.label("bundletool build-apks");
        List<String> command = new ArrayList<>();
        command.add(
                JdkFingerprint.java(Path.of(System.getProperty("java.home"))).toString());
        command.add("-cp");
        command.add(Classpaths.join(ManifestStep.jarsIn(bundletool)));
        command.add("com.android.tools.build.bundletool.BundleToolMain");
        command.add("build-apks");
        command.add("--bundle=" + aab.toAbsolutePath());
        command.add("--output=" + apks.toAbsolutePath());
        command.add("--mode=universal");
        command.add("--aapt2=" + aapt2.toAbsolutePath());
        try (Signing.PasswordFile pass = Signing.passwordFile(DebugKeystore.PASSWORD)) {
            command.addAll(signingFlags(keystore, pass));
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("bundletool build-apks failed:\n" + output);
            }
        }
        // universal.apks is a zip: universal.apk + toc.pb.
        Path universal = work.resolve("universal.apk");
        try (ZipFile zip = new ZipFile(apks.toFile())) {
            var entry = zip.getEntry("universal.apk");
            if (entry == null) {
                throw new IllegalStateException("bundletool build-apks produced no universal.apk");
            }
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, universal, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return universal;
    }

    /**
     * bundletool's signing flags for the stable debug identity.
     *
     * <p>{@code file:}, not {@code pass:} — bundletool's {@code Password.createFromStringValue}
     * reads the first line of the named file, so the value stays off a world-readable argv. The
     * debug password is a published constant and leaked nothing on {@code pass:}; this is the
     * plugin speaking one dialect, and it is the shape a release identity would require.
     */
    static List<String> signingFlags(Path keystore, Signing.PasswordFile pass) {
        return List.of(
                "--ks=" + keystore.toAbsolutePath(),
                "--ks-pass=file:" + pass.arg(),
                "--ks-key-alias=" + DebugKeystore.ALIAS,
                "--key-pass=file:" + pass.arg());
    }

    /** {@code --adb <path>} override, else the provisioned platform-tools binary. */
    private static Path adbPath(PluginCommandExec exec) {
        List<String> args = exec.args();
        for (int i = 0; i < args.size() - 1; i++) {
            if ("--adb".equals(args.get(i))) return Path.of(args.get(i + 1));
        }
        return exec.requireExtra("adb");
    }

    /** Fork adb, streaming its output through the command channel; returns its exit code. */
    private static int adb(PluginCommandExec exec, Path adb, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(adb.toAbsolutePath().toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) exec.out("  " + line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            exec.out("adb " + args[0] + " failed (exit " + exit + ") — is a device connected? (`adb devices`)");
        }
        return exit;
    }

    /**
     * The MAIN/LAUNCHER activity from the module's manifest, as {@code am start} wants it —
     * relative names ({@code .MainActivity}) resolve against the app id.
     */
    static String launcherActivity(Path manifest, String namespace) throws Exception {
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("no AndroidManifest.xml at " + manifest);
        }
        NodeList activities = DomXml.parse(manifest).getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            NodeList actions = activity.getElementsByTagName("action");
            boolean main = false;
            for (int j = 0; j < actions.getLength(); j++) {
                if ("android.intent.action.MAIN".equals(((Element) actions.item(j)).getAttribute("android:name"))) {
                    main = true;
                }
            }
            if (!main) continue;
            String name = activity.getAttribute("android:name");
            if (name.startsWith(".")) return namespace + name;
            return name;
        }
        throw new IllegalStateException(
                "no MAIN/LAUNCHER activity in " + manifest + " — declare one to make the app launchable");
    }
}
