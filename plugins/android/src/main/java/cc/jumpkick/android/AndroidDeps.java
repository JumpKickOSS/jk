// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * The Android view of the module's runtime entries: every dependency whose artifact is an AAR
 * container (a remote androidx library or a workspace {@code [android] library} sibling), in
 * classpath order — the deterministic merge order for resources, assets and native libs. One
 * precedence rule everywhere, matching AGP's: among AARs the earlier classpath entry wins, and the
 * module's own files beat every AAR.
 */
final class AndroidDeps {

    private AndroidDeps() {}

    /** One AAR dependency: its exploded container and the pieces the steps consume. */
    record Aar(String fileName, Path container) {

        Path res() {
            return container.resolve("res");
        }

        Path manifest() {
            return container.resolve("AndroidManifest.xml");
        }

        Path rTxt() {
            return container.resolve("R.txt");
        }

        boolean hasRes() throws IOException {
            if (!Files.isDirectory(res())) return false;
            try (var listing = Files.list(res())) {
                return listing.findFirst().isPresent();
            }
        }

        /** The AAR's package/namespace, parsed from its manifest's {@code package} attribute. */
        String namespace() throws IOException {
            if (!Files.isRegularFile(manifest())) return null;
            String xml = Files.readString(manifest());
            Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            return m.find() ? m.group(1) : null;
        }
    }

    /** The AAR containers among {@code entries}, in entry (classpath) order. */
    static List<Aar> aars(List<PackageIo.RuntimeEntry> entries) {
        List<Aar> out = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : entries) {
            if (e.container() != null) out.add(new Aar(e.fileName(), e.container()));
        }
        return out;
    }

    /**
     * The merged {@code assets/} view: the module's own {@code assets/} collected first, then AAR
     * dependencies in classpath order — the first writer wins, so the app beats every AAR and an
     * earlier AAR beats a later one (AGP's precedence). Keys are asset-relative paths
     * ({@code /}-separated).
     */
    static Map<String, Path> mergedAssets(PackageIo io) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        collectTree(androidFile(io.moduleDir(), "assets"), out);
        for (Aar aar : aars(io.runtimeEntries())) {
            collectTree(aar.container().resolve("assets"), out);
        }
        return out;
    }

    /**
     * The module-relative Android file/dir in either layout: {@code <module>/<rel>} (jk's simple
     * layout) or {@code <module>/src/main/<rel>} (the AGP/traditional location). Returns the
     * simple-layout path when neither exists, so error messages name the primary convention.
     */
    static Path androidFile(Path moduleDir, String rel) {
        Path simple = moduleDir.resolve(rel);
        if (Files.exists(simple)) return simple;
        Path traditional = moduleDir.resolve("src/main").resolve(rel);
        return Files.exists(traditional) ? traditional : simple;
    }

    /** AAR native libs: {@code jni/<abi>/*.so} → APK {@code lib/<abi>/*.so} keys. */
    static Map<String, Path> nativeLibs(PackageIo io) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Aar aar : aars(io.runtimeEntries())) {
            collectTree(aar.container().resolve("jni"), out);
        }
        return out;
    }

    /** Collect {@code root}'s files under relative keys — the first writer of a key wins. */
    private static void collectTree(Path root, Map<String, Path> out) throws IOException {
        // Collected then sorted, rather than sorted inside a stream: the walk hands over each
        // entry's attributes and re-resolving every path to ask isRegularFile again was the cost
        // (JK-1041 via JK-1031's owner).
        List<Path> found = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (file, attrs) -> found.add(file));
        found.sort(Comparator.naturalOrder());
        for (Path f : found) {
            out.putIfAbsent(root.relativize(f).toString().replace('\\', '/'), f);
        }
    }

    /** Extract the per-OS aapt2 binary from its Maven wrapper jar into {@code destDir}. */
    static Path extractAapt2(Path aapt2Jar, Path destDir) throws IOException {
        String binaryName = Os.isWindows() ? "aapt2.exe" : "aapt2";
        Path out = Files.createDirectories(destDir).resolve(binaryName);
        try (ZipFile zip = new ZipFile(aapt2Jar.toFile())) {
            var entry = zip.getEntry(binaryName);
            if (entry == null) {
                throw new IOException(
                        "no " + binaryName + " inside " + aapt2Jar.getFileName() + " — wrong classifier for this OS?");
            }
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!out.toFile().setExecutable(true) && !PathUtil.isRunnable(out)) {
            throw new IOException("cannot mark " + out + " executable");
        }
        return out;
    }

    /**
     * Copy the R8 retrace artifacts ({@code mapping.txt}, {@code seeds.txt}, {@code usage.txt})
     * from the step's cached scratch to the module's stable {@code target/r8/} — the path release
     * tooling (Play upload, crash retrace) reads.
     */
    static void copyRetraceArtifacts(PackageIo io) throws IOException {
        Path mapping =
                io.stepOutput("android-r8").map(dir -> dir.resolve("mapping")).orElse(null);
        if (mapping == null || !Files.isDirectory(mapping)) return;
        Path targetR8 = io.artifactPath().getParent().getParent().resolve("r8");
        Files.createDirectories(targetR8);
        try (var listing = Files.list(mapping)) {
            for (Path file : (Iterable<Path>) listing.sorted()::iterator) {
                Files.copy(file, targetR8.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
