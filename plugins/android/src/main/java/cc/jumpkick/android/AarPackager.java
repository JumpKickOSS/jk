// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipOutputStream;

/**
 * {@code aar} packager for library modules: merged manifest, classes.jar (no R classes), raw
 * {@code res/}, {@code R.txt}; also emits a sibling classes jar for workspace compile.
 */
final class AarPackager {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private AarPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path aar = io.artifactPath();
        Files.createDirectories(aar.getParent());
        Path resStep = io.stepOutput("android-res").orElse(null);
        Path manifestStep = io.stepOutput("android-manifest").orElse(null);
        Path manifest = manifestStep == null ? null : manifestStep.resolve("merged/AndroidManifest.xml");
        if (manifest == null || !Files.isRegularFile(manifest)) {
            throw new IllegalStateException("aar packaging needs the merged manifest — android-manifest did not run");
        }

        // classes.jar (R classes excluded) — written once, reused inside the AAR and as the
        // conventional sibling jar.
        String aarName = aar.getFileName().toString();
        Path classesJar = aar.resolveSibling(aarName.substring(0, aarName.length() - ".aar".length()) + ".jar");
        io.label("classes.jar");
        writeClassesJar(io.classesDir(), classesJar);

        io.label(aarName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aar))) {
            ZIP.writeEntry(zip, "AndroidManifest.xml", manifest);
            ZIP.writeEntry(zip, "classes.jar", classesJar);
            if (resStep != null) {
                Path rTxt = resStep.resolve("packaged/R.txt");
                if (Files.isRegularFile(rTxt)) ZIP.writeEntry(zip, "R.txt", rTxt);
                Path rawRes = resStep.resolve("raw-res");
                if (Files.isDirectory(rawRes)) {
                    for (Path file : ResourceStep.filesUnder(rawRes, "")) {
                        ZIP.writeEntry(
                                zip, "res/" + rawRes.relativize(file).toString().replace('\\', '/'), file);
                    }
                }
            }
        }
    }

    /**
     * Jar the classes dir, excluding the generated {@code R} / {@code R$*} classes and the compile
     * freshness stamps — a stamp body is a wall clock, and this jar ships inside the AAR.
     */
    static void writeClassesJar(Path classesDir, Path jar) throws IOException {
        List<Path> files = ResourceStep.filesUnder(classesDir, "");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Path file : files) {
                String rel = classesDir.relativize(file).toString().replace('\\', '/');
                String name = file.getFileName().toString();
                if (name.equals("R.class") || (name.startsWith("R$") && name.endsWith(".class"))) continue;
                if (BuildStamps.isStampFile(rel)) continue;
                ZIP.writeEntry(zip, rel, file);
            }
        }
    }
}
