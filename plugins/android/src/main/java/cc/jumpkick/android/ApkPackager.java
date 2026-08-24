// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * {@code apk} packager: assemble from {@code resources.ap_} + dex, then v1+v2 debug-sign with a
 * generated {@code androiddebugkey} keystore.
 */
final class ApkPackager {

    /**
     * 1980-02-01T00:00:00Z — the one pinned instant every jk archive writer stamps entries with.
     * Applied via {@link ZipEntry#setTimeLocal}, never {@code setTime}: setTime's DOS-time
     * conversion runs through the JVM's default timezone, so the same inputs would produce
     * different bytes on a host with a different {@code $TZ}. The value is the zip epoch's first
     * month — anything before 1980 is unrepresentable in DOS time and costs an extended-timestamp
     * extra field (18 bytes) on every entry.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.ofEpochSecond(318_211_200L, 0, ZoneOffset.UTC);

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
            Signing.sign(Signing.debug(io, work), unsigned, out);
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
                write(zip, entry.getName(), bytes, entry.getMethod());
            }
            List<Path> dexFiles = ResourceStep.filesUnder(dexDir, ".dex");
            if (dexFiles.isEmpty()) {
                throw new IOException("no .dex files under " + dexDir);
            }
            for (Path dex : dexFiles) {
                write(zip, dex.getFileName().toString(), Files.readAllBytes(dex), ZipEntry.DEFLATED);
            }
            for (var asset : AndroidDeps.mergedAssets(io).entrySet()) {
                write(zip, "assets/" + asset.getKey(), Files.readAllBytes(asset.getValue()), ZipEntry.DEFLATED);
            }
            for (var lib : AndroidDeps.nativeLibs(io).entrySet()) {
                write(zip, "lib/" + lib.getKey(), Files.readAllBytes(lib.getValue()), ZipEntry.STORED);
            }
        }
    }

    /** One entry, pinned to {@link #ENTRY_TIME} so the archive is byte-identical run to run. */
    static void write(ZipOutputStream zip, String name, byte[] bytes, int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            entry.setSize(bytes.length);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
