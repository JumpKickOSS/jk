// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * {@code aab} packager: bundletool base-module layout, {@code build-bundle}, then jarsign (AAB is
 * a signed jar). R8 retrace artifacts land under {@code target/r8/}.
 */
final class AabPackager {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private AabPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path protoPackage = io.stepOutput("android-res")
                .map(dir -> dir.resolve("packaged").resolve("resources-proto.ap_"))
                .filter(Files::isRegularFile)
                .orElseThrow(() -> new IllegalStateException(
                        "android-res produced no proto resource package — release links twice"));
        Path dexDir = ApkPackager.dexOutput(io);
        Path bundletool = io.extra("bundletool")
                .orElseThrow(() -> new IllegalStateException("bundletool tool artifact not provided"));

        Path out = io.artifactPath();
        Path work = Files.createTempDirectory("jk-aab-");

        io.label("assemble base module");
        Path baseZip = work.resolve("base.zip");
        assembleBase(io, protoPackage, dexDir, baseZip);

        io.label("bundletool build-bundle");
        Path unsigned = work.resolve("unsigned.aab");
        TaskExec.ToolRun.Result bundle = io.java()
                .classpath(ManifestStep.jarsIn(bundletool))
                .mainClass("com.android.tools.build.bundletool.BundleToolMain")
                .arg("build-bundle")
                .arg("--modules=" + baseZip.toAbsolutePath())
                .arg("--output=" + unsigned.toAbsolutePath())
                .run();
        if (bundle.exit() != 0) {
            throw new IllegalStateException("bundletool build-bundle failed:\n" + bundle.output());
        }

        io.label("sign bundle");
        signBundle(io, unsigned, out);
        AndroidDeps.copyRetraceArtifacts(io);
    }

    /**
     * The base-module zip in bundletool's layout: {@code manifest/AndroidManifest.xml} (proto),
     * {@code resources.pb}, {@code res/**}, {@code dex/classes*.dex}, {@code assets/**},
     * {@code lib/<abi>/*.so}, everything else from the proto link under {@code root/}.
     *
     * <p>Package-private, like {@link #jarsignerArgs}, so a test can read the layout this produces
     * without a bundletool fork: the whole contract is which entry lands under which prefix, and
     * bundletool's only answer to a wrong one is a rejected bundle at upload time.
     */
    static void assembleBase(PackageIo io, Path protoPackage, Path dexDir, Path baseZip) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(baseZip));
                ZipFile in = new ZipFile(protoPackage.toFile())) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                String name = entry.getName();
                String target;
                if (name.equals("AndroidManifest.xml")) {
                    target = "manifest/AndroidManifest.xml";
                } else if (name.equals("resources.pb") || name.startsWith("res/")) {
                    target = name;
                } else {
                    target = "root/" + name;
                }
                ZIP.writeEntry(zip, target, bytes, entry.getMethod());
            }
            List<Path> dexFiles = ResourceStep.filesUnder(dexDir, ".dex");
            if (dexFiles.isEmpty()) {
                throw new IOException("no .dex files under " + dexDir);
            }
            for (Path dex : dexFiles) {
                ZIP.writeEntry(zip, "dex/" + dex.getFileName(), Files.readAllBytes(dex), ZipEntry.DEFLATED);
            }
            for (var asset : AndroidDeps.mergedAssets(io).entrySet()) {
                ZIP.writeEntry(
                        zip, "assets/" + asset.getKey(), Files.readAllBytes(asset.getValue()), ZipEntry.DEFLATED);
            }
            for (var lib : AndroidDeps.nativeLibs(io).entrySet()) {
                ZIP.writeEntry(zip, "lib/" + lib.getKey(), Files.readAllBytes(lib.getValue()), ZipEntry.DEFLATED);
            }
        }
    }

    /** jarsigner over the bundle: the release identity when configured, else the debug keystore. */
    private static void signBundle(PackageIo io, Path unsigned, Path out) throws Exception {
        Path keystore;
        String storePass;
        String keyPass;
        String alias;
        if (Signing.hasReleaseConfig(io)) {
            keystore = Path.of(io.config().string("signing.store-file"));
            alias = io.config().string("signing.key-alias");
            storePass = io.secret("signing.store-password").orElse("");
            keyPass = io.secret("signing.key-password").orElse(storePass);
        } else {
            keystore = Signing.debugKeystore(io);
            alias = DebugKeystore.ALIAS;
            storePass = DebugKeystore.PASSWORD;
            keyPass = DebugKeystore.PASSWORD;
        }
        Files.copy(unsigned, out, StandardCopyOption.REPLACE_EXISTING);
        // The release password reaches jarsigner on a 0600 file, not on argv — see
        // Signing.passwordFile. Both files are gone before this method returns.
        try (Signing.PasswordFile store = Signing.passwordFile(storePass);
                Signing.PasswordFile key = Signing.passwordFile(keyPass)) {
            TaskExec.ToolRun.Result signed = io.tool("jarsigner")
                    .args(jarsignerArgs(keystore, store, key, out, alias))
                    .run();
            if (signed.exit() != 0) {
                // jarsigner's stdout/stderr alone: the command line names two temp paths and
                // nothing a reader could act on.
                throw new IllegalStateException("jarsigner failed:\n" + signed.output());
            }
        }
    }

    /**
     * jarsigner's argv. A method rather than a builder chain so a test can read the command line
     * this build would appear under in {@code ps} — the point of the {@code :file} forms being what
     * is <em>not</em> in it.
     */
    static List<String> jarsignerArgs(
            Path keystore, Signing.PasswordFile store, Signing.PasswordFile key, Path out, String alias) {
        return List.of(
                "-keystore",
                keystore.toAbsolutePath().toString(),
                "-storepass:file",
                store.arg(),
                "-keypass:file",
                key.arg(),
                out.toAbsolutePath().toString(),
                alias);
    }
}
