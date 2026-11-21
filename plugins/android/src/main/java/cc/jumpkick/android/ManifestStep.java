// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code android-manifest} step: Google's manifest-merger → {@code merged/AndroidManifest.xml}
 * with namespace package and {@code <uses-sdk>} from min/compile SDK. Runs as a {@code transitive}
 * step-dependency classpath. {@link TestConfigStep} merges the unit-test manifest with the same
 * merger.
 */
final class ManifestStep {

    private ManifestStep() {}

    static void run(TaskExec exec) throws Exception {
        // Dependency (AAR) manifests join the merge in classpath order — app first (--main
        // wins), exactly AGP's precedence. APP builds only: a library merges its OWN manifest
        // (AGP semantics — the full closure merges once, at the consuming app; merging dep
        // manifests into a library trips cross-library conflicts like androidx.startup's
        // per-package InitializationProvider authorities).
        boolean library = exec.config().bool("library", false);
        List<Path> libManifests = library ? List.of() : aarManifests(exec.runtimeEntries());
        merge(exec, libManifests, library, exec.outputDir("merged").resolve("AndroidManifest.xml"));
    }

    /** Every AAR manifest among {@code entries}, in classpath order. */
    static List<Path> aarManifests(List<PackageIo.RuntimeEntry> entries) {
        List<Path> out = new ArrayList<>();
        for (AndroidDeps.Aar aar : AndroidDeps.aars(entries)) {
            if (Files.isRegularFile(aar.manifest())) out.add(aar.manifest());
        }
        return out;
    }

    /**
     * Merge the module's manifest with {@code libManifests} into {@code merged}: in the merger's
     * LIBRARY mode when {@code libraryMode}, else as an application.
     */
    static void merge(TaskExec exec, List<Path> libManifests, boolean libraryMode, Path merged) throws Exception {
        Path manifest = AndroidDeps.androidFile(exec.moduleDir(), "AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            // Optional since AGP 7 — a manifest-less library gets a synthesized minimal one
            // (package + uses-sdk injected by the merger properties below, same as AGP).
            // An APPLICATION still needs a real manifest: it declares the launchable surface.
            if (!exec.config().bool("library", false)) {
                throw new IllegalStateException("no AndroidManifest.xml at the module root or src/main/");
            }
            manifest = exec.scratch().resolve("synthesized/AndroidManifest.xml");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"/>\n");
        }
        Path mergerLib = exec.requireExtra("manifest-merger");
        String namespace = exec.config().string("namespace");
        // The merger substitutes PACKAGE for ${applicationId}, so an app merges as its suffixed
        // id. It qualifies the main manifest's relative class names against that manifest's own
        // package, so a copy that names none is given the namespace, where the classes live.
        String applicationId = libraryMode ? namespace : AndroidDeps.applicationId(exec.config());
        if (!applicationId.equals(namespace)
                && !DomXml.parse(manifest).getDocumentElement().hasAttribute("package")) {
            Path named = exec.scratch().resolve("namespaced/AndroidManifest.xml");
            Files.createDirectories(named.getParent());
            Files.writeString(named, withPackage(Files.readString(manifest), namespace));
            manifest = named;
        }
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        long minSdk = exec.config().intValue("min-sdk", 0);
        Files.createDirectories(merged.getParent());

        // The merger's --libs takes the platform path separator, same wire format as a -cp.
        String libs = Classpaths.join(libManifests);

        exec.label("merge manifest");
        TaskExec.ToolRun merger = exec.java()
                .classpath(jarsIn(mergerLib))
                .mainClass("com.android.manifmerger.Merger")
                .arg("--main")
                .arg(manifest.toAbsolutePath().toString());
        if (libs.length() > 0) {
            merger.arg("--libs").arg(libs);
        }
        // A LIBRARY merge runs in the merger's LIBRARY mode (AGP semantics): placeholders like
        // ${applicationId} stay literal — androidx.startup's per-package provider authorities
        // must all become the APP's id at the one real merge, not each library's namespace, or
        // the app merge sees N conflicting values. An app merge substitutes for real.
        if (libraryMode) {
            merger.arg("--merge-type").arg("LIBRARY");
        } else {
            // The app merge consumes tools: directives (tools:node="remove" etc.) — they must
            // not reach aapt2, which rejects e.g. a <property> left carrying only tools attrs.
            merger.arg("--remove-tools-declarations");
        }
        TaskExec.ToolRun.Result result = merger.arg("--property")
                .arg("PACKAGE=" + applicationId)
                .arg("--property")
                .arg("MIN_SDK_VERSION=" + minSdk)
                .arg("--property")
                .arg("TARGET_SDK_VERSION=" + compileSdk)
                .arg("--out")
                .arg(merged.toAbsolutePath().toString())
                .run();
        if (result.exit() != 0 || !Files.isRegularFile(merged)) {
            throw new IllegalStateException("manifest-merger failed:\n" + result.output());
        }
    }

    /**
     * {@code xml} with {@code package="namespace"} on its root {@code <manifest>}, every other byte
     * as written, so the merger's line numbers still match the module's file. Comments and the XML
     * declaration before the root are skipped; a DOCTYPE never gets here, since the parse that
     * checked for a package attribute refuses one.
     */
    static String withPackage(String xml, String namespace) {
        int open = xml.indexOf('<');
        while (open >= 0 && (xml.startsWith("<!--", open) || xml.startsWith("<?", open))) {
            String close = xml.startsWith("<!--", open) ? "-->" : "?>";
            int end = xml.indexOf(close, open);
            open = end < 0 ? -1 : xml.indexOf('<', end + close.length());
        }
        int name = open + "<manifest".length();
        if (open >= 0
                && xml.startsWith("<manifest", open)
                && name < xml.length()
                && !Character.isLetterOrDigit(xml.charAt(name))) {
            return xml.substring(0, name) + " package=\"" + namespace + "\"" + xml.substring(name);
        }
        throw new IllegalStateException("the module's AndroidManifest.xml has no <manifest> root element");
    }

    /** The jars of a {@code transitive} step-dependency's lib directory, sorted. */
    static List<Path> jarsIn(Path libDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var listing = Files.list(libDir)) {
            listing.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no jars in the manifest-merger tool directory: " + libDir);
        }
        return jars;
    }
}
