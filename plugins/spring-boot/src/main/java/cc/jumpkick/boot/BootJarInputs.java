// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Boot-launcher facts read off a {@link PackageIo}, shared by the two frameworks that produce
 * this archive: {@code spring-boot} and {@code grails} (a Grails 8 jar IS a Boot 4.1 jar). Only the
 * inputs are shared — what each framework then puts <em>into</em> the jar differs and stays in its
 * own plugin: Boot adds AOT roots, an SBOM and the jarmode tools jar; Grails adds a
 * {@code Grails-Version} attribute and nothing else.
 *
 * @param bootVersion the Boot line this jar actually carries, resolved
 * @param startClass the application entry point the launcher hands control to
 * @param loaderJar the {@code spring-boot-loader} jar exploded at the archive root
 * @param libs the runtime closure as nested {@code BOOT-INF/lib} entries, in lock order
 */
public record BootJarInputs(String bootVersion, String startClass, Path loaderJar, List<BootJarPackager.Lib> libs) {

    /** Boot's own coordinate. Its resolved version in the closure IS the jar's Boot version. */
    private static final String BOOT_GROUP = "org.springframework.boot";

    private static final String BOOT_ARTIFACT = "spring-boot";

    /** The engine-fetched artifact both packagers explode at the archive root. */
    static final String LOADER_EXTRA = "spring-boot-loader";

    /**
     * Read the inputs, resolving the Boot version from the runtime closure rather than from the
     * declared {@code version} selector.
     *
     * <p>The selector reaches the worker unresolved — {@code latest}, {@code ^4}, {@code =4.1.0}
     * are all legal spellings of the key — so writing it into {@code Spring-Boot-Version} published
     * jars claiming to be built against "latest". The closure has the answer the manifest is asking
     * for: whatever {@code org.springframework.boot:spring-boot} resolved to is the Boot the app
     * will actually run on. Its absence is not a manifest problem to paper over — a jar whose
     * nested libs have no {@code spring-boot} cannot start its {@code Start-Class} — so it fails
     * here, where the cause is still legible.
     */
    public static BootJarInputs read(PackageIo io) throws IOException {
        String startClass = io.project().mainClass();
        if (startClass == null || startClass.isBlank()) {
            throw new IOException("no application main class — a Boot-launcher jar needs a Start-Class");
        }
        Path loaderJar = io.extra(LOADER_EXTRA)
                .orElseThrow(() -> new IOException(LOADER_EXTRA + " artifact missing from the packager inputs"));

        List<BootJarPackager.Lib> libs = new ArrayList<>();
        String bootVersion = "";
        for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
            // A container entry with no classes.jar has nothing to nest under BOOT-INF/lib.
            Path jar = entry.jar();
            if (jar != null) libs.add(new BootJarPackager.Lib(entry.fileName(), jar, entry.snapshot(), entry.group()));
            if (BOOT_GROUP.equals(entry.group()) && BOOT_ARTIFACT.equals(entry.artifact())) {
                bootVersion = entry.version();
            }
        }
        if (bootVersion.isBlank()) {
            throw new IOException("the resolved runtime closure carries no " + BOOT_GROUP + ":" + BOOT_ARTIFACT
                    + ", so this jar has no Spring Boot to launch and no Boot version to record —"
                    + " declare a Spring Boot starter in [dependencies]");
        }
        return new BootJarInputs(bootVersion, startClass, loaderJar, List.copyOf(libs));
    }
}
