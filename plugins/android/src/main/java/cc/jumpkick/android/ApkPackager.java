// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * {@code apk} packager: assemble from {@code resources.ap_} + dex, then v1+v2 sign — with the
 * configured release identity, else the stable {@link DebugKeystore}.
 */
final class ApkPackager {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private ApkPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path resPackage = io.stepOutput("android-res")
                .map(dir -> dir.resolve("packaged").resolve("resources.ap_"))
                .filter(Files::isRegularFile)
                .orElseThrow(() -> new IllegalStateException("android-res produced no resources.ap_"));
        Path dexDir = dexOutput(io);

        Path out = io.artifactPath();
        Path work = Files.createTempDirectory("jk-apk-");
        Path unsigned = work.resolve("unsigned.apk");

        io.label("assemble " + out.getFileName());
        assemble(io, resPackage, dexDir, unsigned);

        if (Signing.hasReleaseConfig(io)) {
            io.label("sign (release)");
            Signing.sign(Signing.release(io), unsigned, out);
        } else {
            io.label("sign (debug)");
            Signing.sign(Signing.debug(io), unsigned, out);
        }
        AndroidDeps.copyRetraceArtifacts(io);
    }

    /** The dex step's output — {@code android-r8} on a minified build, else {@code android-dex}. */
    static Path dexOutput(PackageIo io) {
        return io.stepOutput("android-r8")
                .or(() -> io.stepOutput("android-dex"))
                .map(dir -> dir.resolve("dex"))
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException("no dex output — did the dex/r8 step run?"));
    }

    /**
     * The unsigned APK: every entry of the aapt2-linked package copied with its compression
     * preserved ({@code resources.arsc} must stay STORED), each {@code classes*.dex}, the merged
     * {@code assets/} (module wins over AAR deps), and AAR native libs under {@code lib/<abi>/}
     * (STORED — apksig's output engine page-aligns uncompressed {@code .so} entries).
     */
    private static void assemble(PackageIo io, Path resPackage, Path dexDir, Path unsigned) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsigned));
                ZipFile in = new ZipFile(resPackage.toFile())) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                ZIP.writeEntry(zip, entry.getName(), bytes, entry.getMethod());
            }
            List<Path> dexFiles = ResourceStep.filesUnder(dexDir, ".dex");
            if (dexFiles.isEmpty()) {
                throw new IOException("no .dex files under " + dexDir);
            }
            for (Path dex : dexFiles) {
                ZIP.writeEntry(zip, dex.getFileName().toString(), Files.readAllBytes(dex), ZipEntry.DEFLATED);
            }
            for (var asset : AndroidDeps.mergedAssets(io).entrySet()) {
                ZIP.writeEntry(
                        zip, "assets/" + asset.getKey(), Files.readAllBytes(asset.getValue()), ZipEntry.DEFLATED);
            }
            for (var lib : AndroidDeps.nativeLibs(io).entrySet()) {
                ZIP.writeEntry(zip, "lib/" + lib.getKey(), Files.readAllBytes(lib.getValue()), ZipEntry.STORED);
            }
        }
    }
}
