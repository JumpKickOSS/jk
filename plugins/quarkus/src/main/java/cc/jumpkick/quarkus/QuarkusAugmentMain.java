// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Forked entry point for Quarkus production augmentation (JK-1160/1202).
 *
 * <p>Args: {@code projectRoot classesDir targetDir baseName group artifact version runtimeListFile
 * quarkusVersion} where {@code runtimeListFile} is lines of {@code
 * group:artifact:version\\t/abs/path.jar}.
 *
 * <p>Installs the application classes as a local Maven artifact (so bootstrap's model resolver can
 * see {@code group:artifact:version}) and writes a synthetic POM listing runtime deps + importing
 * the Quarkus platform BOM. Deployment expansion is left to Quarkus bootstrap against Maven Central
 * / the local repo.
 */
public final class QuarkusAugmentMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            System.err.println(
                    "usage: QuarkusAugmentMain projectRoot classesDir targetDir baseName group artifact version runtimeListFile quarkusVersion");
            System.exit(2);
        }
        Path projectRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path targetDir = Path.of(args[2]).toAbsolutePath().normalize();
        String baseName = args[3];
        String group = args[4];
        String artifact = args[5];
        String version = args[6];
        Path runtimeList = Path.of(args[7]).toAbsolutePath().normalize();
        String quarkusVersion = args[8];

        List<RuntimeCoord> runtime = parseRuntimeList(runtimeList);
        Path scratch = Files.createDirectories(targetDir.resolve(".jk-quarkus-bootstrap"));
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        Path projectDir = Files.createDirectories(scratch.resolve("project"));

        // 1. Synthetic project POM listing runtime deps + platform BOM import.
        Path pom = projectDir.resolve("pom.xml");
        Files.writeString(
                pom, syntheticPom(group, artifact, version, quarkusVersion, runtime), StandardCharsets.UTF_8);

        // 2. Install app classes as the GAV jar under the local repo so model resolve succeeds.
        Path gPath = localRepo;
        for (String part : group.split("\\.")) {
            gPath = gPath.resolve(part);
        }
        Path artifactDir = Files.createDirectories(gPath.resolve(artifact).resolve(version));
        Path appJar = artifactDir.resolve(artifact + "-" + version + ".jar");
        jarClasses(classesDir, appJar);
        Files.writeString(
                artifactDir.resolve(artifact + "-" + version + ".pom"),
                syntheticPom(group, artifact, version, quarkusVersion, runtime),
                StandardCharsets.UTF_8);

        // Also install runtime jars into the local repo so offline-ish resolve can find them.
        for (RuntimeCoord d : runtime) {
            installJar(localRepo, d);
        }

        System.setProperty("maven.repo.local", localRepo.toString());

        java.util.Properties buildProps = new java.util.Properties();
        // Platform property expansion (from quarkus-bom) when platform imports are incomplete.
        buildProps.setProperty(
                "platform.quarkus.native.builder-image",
                "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
        buildProps.setProperty("quarkus.platform.version", quarkusVersion);
        buildProps.setProperty("quarkus.platform.group-id", "io.quarkus.platform");
        buildProps.setProperty("quarkus.platform.artifact-id", "quarkus-bom");

        MavenArtifactResolver resolver = new MavenArtifactResolver(new BootstrapMavenContext(
                BootstrapMavenContext.config()
                        .setCurrentProject(projectDir.toString())
                        .setUserSettings(null)
                        .setOffline(false)
                        .setLocalRepository(localRepo.toString())
                        .setWorkspaceDiscovery(false)));

        QuarkusBootstrap bootstrap = QuarkusBootstrap.builder()
                .setProjectRoot(projectDir)
                .setApplicationRoot(classesDir)
                .setTargetDirectory(targetDir)
                .setBaseName(baseName)
                .setMode(QuarkusBootstrap.Mode.PROD)
                // Flat classpath for the first cut — isolation needs a fuller deployment model.
                .setIsolateDeployment(false)
                .setLocalProjectDiscovery(false)
                .setMavenArtifactResolver(resolver)
                .setBuildSystemProperties(buildProps)
                .setRebuild(true)
                .build();

        try (CuratedApplication curated = bootstrap.bootstrap()) {
            AugmentResult result = curated.createAugmentor().createProductionApplication();
            if (result.getJar() == null || result.getJar().getPath() == null) {
                throw new IllegalStateException("Quarkus augmentation produced no jar");
            }
            Path out = result.getJar().getPath();
            System.out.println("jk-quarkus-augment: " + out);
            if (!Files.isRegularFile(out)) {
                throw new IllegalStateException("augment jar missing: " + out);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            Throwable c = e;
            while (c != null) {
                for (Throwable s : c.getSuppressed()) {
                    s.printStackTrace(System.err);
                }
                c = c.getCause();
            }
            throw e;
        }
    }

    private record RuntimeCoord(String group, String artifact, String version, Path jar) {}

    private static List<RuntimeCoord> parseRuntimeList(Path file) throws Exception {
        List<RuntimeCoord> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad runtime list line: " + line);
            }
            String[] gav = parts[0].split(":", 3);
            if (gav.length != 3) {
                throw new IllegalArgumentException("bad GAV: " + parts[0]);
            }
            out.add(new RuntimeCoord(gav[0], gav[1], gav[2], Path.of(parts[1])));
        }
        return out;
    }

    private static void installJar(Path localRepo, RuntimeCoord d) throws IOException {
        if (d.jar == null || !Files.isRegularFile(d.jar)) return;
        if (d.group.startsWith("unknown")) return;
        Path dir = localRepo;
        for (String part : d.group.split("\\.")) {
            dir = dir.resolve(part);
        }
        dir = Files.createDirectories(dir.resolve(d.artifact).resolve(d.version));
        Path dest = dir.resolve(d.artifact + "-" + d.version + ".jar");
        if (!Files.exists(dest)) {
            Files.copy(d.jar, dest);
        }
    }

    private static void jarClasses(Path classesDir, Path outJar) throws IOException {
        Files.createDirectories(outJar.getParent());
        try (OutputStream fos = Files.newOutputStream(outJar);
                JarOutputStream jos = new JarOutputStream(fos)) {
            if (!Files.isDirectory(classesDir)) return;
            Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = classesDir.relativize(file).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(name));
                    Files.copy(file, jos);
                    jos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static String syntheticPom(
            String group, String artifact, String version, String quarkusVersion, List<RuntimeCoord> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>").append(xml(group)).append("</groupId>\n");
        sb.append("  <artifactId>").append(xml(artifact)).append("</artifactId>\n");
        sb.append("  <version>").append(xml(version)).append("</version>\n");
        sb.append("  <dependencyManagement>\n");
        sb.append("    <dependencies>\n");
        sb.append("      <dependency>\n");
        sb.append("        <groupId>io.quarkus.platform</groupId>\n");
        sb.append("        <artifactId>quarkus-bom</artifactId>\n");
        sb.append("        <version>").append(xml(quarkusVersion)).append("</version>\n");
        sb.append("        <type>pom</type>\n");
        sb.append("        <scope>import</scope>\n");
        sb.append("      </dependency>\n");
        sb.append("    </dependencies>\n");
        sb.append("  </dependencyManagement>\n");
        Map<String, RuntimeCoord> unique = new LinkedHashMap<>();
        for (RuntimeCoord d : deps) {
            if (d.group.startsWith("unknown")) continue;
            unique.putIfAbsent(d.group + ":" + d.artifact, d);
        }
        sb.append("  <dependencies>\n");
        for (RuntimeCoord d : unique.values()) {
            sb.append("    <dependency>\n");
            sb.append("      <groupId>").append(xml(d.group)).append("</groupId>\n");
            sb.append("      <artifactId>").append(xml(d.artifact)).append("</artifactId>\n");
            sb.append("      <version>").append(xml(d.version)).append("</version>\n");
            sb.append("    </dependency>\n");
        }
        sb.append("  </dependencies>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private QuarkusAugmentMain() {}
}
