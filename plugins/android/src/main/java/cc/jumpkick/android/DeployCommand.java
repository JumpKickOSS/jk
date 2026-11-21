// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.PluginCommandExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

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
            exec.out("jk run: no APK or AAB built yet; run jk build first");
            return 1;
        }
        Path adb = adbPath(exec);
        String namespace = exec.config().string("namespace");
        String activity = launcherActivity(AndroidDeps.mergedManifest(artifact), namespace);
        String component = AndroidDeps.applicationId(exec.config()) + "/" + activity;
        // A release AAB deploys locally through bundletool: build-apks --mode universal against
        // the debug identity (local testing — Play signs the real install artifacts), then the
        // extracted universal.apk installs like any APK. The directory it is built in lives
        // exactly as long as the install needs it.
        Path work = artifact.toString().endsWith(".aab") ? Files.createTempDirectory("jk-deploy-") : null;
        try {
            Path apk = work != null ? universalApk(exec, artifact, work) : artifact;

            exec.label("adb install");
            exec.out("Installing " + apk.getFileName() + " …");
            int install = adb(exec, adb, "install", "-r", apk.toAbsolutePath().toString());
            if (install != 0) return install;

            exec.label("am start");
            exec.out("Launching " + component + " …");
            return adb(exec, adb, "shell", "am", "start", "-n", component);
        } finally {
            if (work != null) PathUtil.deleteRecursively(work);
        }
    }

    /** bundletool build-apks --mode universal over the AAB, under {@code work}; the extracted universal.apk. */
    private static Path universalApk(PluginCommandExec exec, Path aab, Path work) throws Exception {
        Path bundletool = exec.requireExtra("bundletool");
        Path apks = work.resolve("universal.apks");
        // The Maven bundletool library embeds no aapt2 — hand it the plugin's own; local-deploy
        // installs sign with the SAME stable debug identity the apk/aab packagers use, so a
        // universal APK from a rebuilt bundle still updates the installed app in place.
        Path aapt2 = AndroidDeps.extractAapt2(exec.requireExtra("aapt2"), work.resolve("tools"));
        Path keystore = DebugKeystore.ensure(Signing.debugStoreDir(exec.config()), exec.javaHome());
        exec.label("bundletool build-apks");
        try (Signing.PasswordFile pass = Signing.passwordFile(DebugKeystore.PASSWORD)) {
            var run = exec.java()
                    .classpath(ManifestStep.jarsIn(bundletool))
                    .mainClass("com.android.tools.build.bundletool.BundleToolMain")
                    .arg("build-apks")
                    .arg("--bundle=" + aab.toAbsolutePath())
                    .arg("--output=" + apks.toAbsolutePath())
                    .arg("--mode=universal")
                    .arg("--aapt2=" + aapt2.toAbsolutePath())
                    .args(signingFlags(keystore, pass))
                    .run();
            if (run.exit() != 0) {
                throw new IllegalStateException("bundletool build-apks failed:\n" + run.output());
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
        int exit = exec.tool(adb).args(List.of(args)).stream(line -> {
            if (!line.isBlank()) exec.out("  " + line);
        });
        if (exit != 0) {
            exec.out("adb " + args[0] + " failed (exit " + exit + "); check adb devices for a connected device");
        }
        return exit;
    }

    /**
     * The first enabled {@code <activity>} or {@code <activity-alias>} of the merged manifest with
     * a MAIN/LAUNCHER intent filter, as {@code am start} wants it: a relative name
     * ({@code .MainActivity}, {@code MainActivity}) resolves against the namespace.
     */
    static String launcherActivity(Path manifest, String namespace) throws Exception {
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("no merged manifest at " + manifest + "; run jk build first");
        }
        Element application = DomXml.childElement(DomXml.parse(manifest).getDocumentElement(), "application");
        for (Element component : DomXml.childElements(application)) {
            String tag = component.getTagName();
            if (!tag.equals("activity") && !tag.equals("activity-alias")) continue;
            if ("false".equals(component.getAttribute("android:enabled"))) continue;
            if (launches(component)) return className(component.getAttribute("android:name"), namespace);
        }
        throw new IllegalStateException(
                "no MAIN/LAUNCHER activity in " + manifest + "; declare one so the app can launch");
    }

    /** Whether one of {@code component}'s intent filters has both the MAIN action and the LAUNCHER category. */
    private static boolean launches(Element component) {
        for (Element filter : DomXml.childElements(component, "intent-filter")) {
            if (names(filter, "action", "android.intent.action.MAIN")
                    && names(filter, "category", "android.intent.category.LAUNCHER")) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(Element filter, String tag, String name) {
        for (Element el : DomXml.childElements(filter, tag)) {
            if (name.equals(el.getAttribute("android:name"))) return true;
        }
        return false;
    }

    /** A manifest class name as a full one: Android reads a name with no dot, or a leading one, as relative. */
    private static String className(String name, String namespace) {
        if (name.startsWith(".")) return namespace + name;
        if (name.indexOf('.') < 0) return namespace + "." + name;
        return name;
    }
}
