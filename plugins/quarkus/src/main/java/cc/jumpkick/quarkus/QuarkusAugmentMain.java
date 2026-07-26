// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Forked entry point for Quarkus production packaging (JK-1160/1202).
 *
 * <p>Args: {@code projectRoot classesDir targetDir baseName group artifact version runtimeListFile
 * quarkusVersion}
 *
 * <p>Strategy: drive {@code quarkus-maven-plugin} via Maven (when {@code mvn} is on PATH) with a
 * synthetic POM that imports the platform BOM and depends on discovered Quarkus extensions. Classes
 * are copied into {@code target/classes}; the plugin writes {@code target/quarkus-app/quarkus-run.jar}
 * which we promote into {@code targetDir}.
 *
 * <p>Pure bootstrap (no Maven) remains a follow-up — SmallRye config mapping under a synthetic
 * ApplicationModel still needs platform-properties wiring that the Maven plugin already owns.
 */
public final class QuarkusAugmentMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            System.err.println(
                    "usage: QuarkusAugmentMain projectRoot classesDir targetDir baseName group artifact version runtimeListFile quarkusVersion");
            System.exit(2);
        }
        Path appProjectRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path targetDir = Path.of(args[2]).toAbsolutePath().normalize();
        String baseName = args[3];
        String group = args[4];
        String artifact = args[5];
        String version = args[6];
        Path runtimeList = Path.of(args[7]).toAbsolutePath().normalize();
        String quarkusVersion = args[8];

        List<RuntimeCoord> runtime = parseRuntimeList(runtimeList);
        List<RuntimeCoord> extensions = discoverExtensions(runtime);
        System.err.println("jk-quarkus-augment: runtime=" + runtime.size() + " extensions=" + extensions.size());

        Path mvn = findMaven();
        if (mvn == null) {
            throw new IllegalStateException(
                    "Quarkus packaging needs `mvn` on PATH for the quarkus-maven-plugin bridge (JK-1160). "
                            + "Install Maven or ensure `mvn` is available.");
        }

        Path scratch = Files.createDirectories(targetDir.resolve(".jk-quarkus-maven"));
        Path projectDir = Files.createDirectories(scratch.resolve("project"));
        Path targetClasses = Files.createDirectories(projectDir.resolve("target/classes"));

        // Copy compiled classes + resources into the synthetic Maven project's target/classes.
        if (Files.isDirectory(classesDir)) {
            copyTree(classesDir, targetClasses);
        }
        Path appProps = appProjectRoot.resolve("src/main/resources/application.properties");
        if (Files.isRegularFile(appProps) && !Files.isRegularFile(targetClasses.resolve("application.properties"))) {
            Files.copy(appProps, targetClasses.resolve("application.properties"), StandardCopyOption.REPLACE_EXISTING);
        }

        Files.writeString(
                projectDir.resolve("pom.xml"),
                mavenPom(group, artifact, version, quarkusVersion, extensions),
                StandardCharsets.UTF_8);

        List<String> cmd = List.of(
                mvn.toString(),
                "-f",
                projectDir.resolve("pom.xml").toString(),
                "-q",
                "package",
                "-DskipTests",
                "-Dquarkus.package.jar.type=fast-jar",
                "-Dquarkus.analytics.disabled=true");
        System.err.println("jk-quarkus-augment: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(projectDir.toFile())
                .redirectErrorStream(true);
        // Prefer a private local repo under scratch so we do not thrash ~/.m2 unnecessarily, but still
        // allow Maven Central for deployment artifacts.
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        // Seed runtime jars into the private local repo for faster offline hits.
        for (RuntimeCoord d : runtime) {
            installJar(localRepo, d);
        }
        pb.environment().put("MAVEN_OPTS", pb.environment().getOrDefault("MAVEN_OPTS", ""));
        List<String> fullCmd = new ArrayList<>(cmd);
        // Inject -Dmaven.repo.local after mvn binary
        fullCmd.add(2, "-Dmaven.repo.local=" + localRepo);
        // Fix: -f is at index 1-2; rebuild cleanly
        fullCmd = new ArrayList<>();
        fullCmd.add(mvn.toString());
        fullCmd.add("-Dmaven.repo.local=" + localRepo);
        fullCmd.add("-f");
        fullCmd.add(projectDir.resolve("pom.xml").toString());
        fullCmd.add("-q");
        fullCmd.add("package");
        fullCmd.add("-DskipTests");
        fullCmd.add("-Dquarkus.package.jar.type=fast-jar");
        fullCmd.add("-Dquarkus.analytics.disabled=true");
        pb.command(fullCmd);

        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
                System.err.println(line);
            }
        }
        boolean finished = proc.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("quarkus-maven-plugin package timed out after 15m\n" + out);
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException(
                    "quarkus-maven-plugin package failed (exit " + proc.exitValue() + "):\n" + out);
        }

        Path quarkusApp = projectDir.resolve("target/quarkus-app");
        Path runJar = quarkusApp.resolve("quarkus-run.jar");
        if (!Files.isRegularFile(runJar)) {
            // Some plugin versions place the runner differently
            try (var walk = Files.walk(projectDir.resolve("target"), 4)) {
                runJar = walk.filter(p -> p.getFileName().toString().equals("quarkus-run.jar"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
            }
        }
        if (runJar == null || !Files.isRegularFile(runJar)) {
            throw new IllegalStateException("quarkus-run.jar not produced under " + projectDir.resolve("target"));
        }

        // Promote quarkus-app layout into the step output root (targetDir).
        Path destApp = targetDir.resolve("quarkus-app");
        if (Files.isDirectory(destApp)) {
            deleteTree(destApp);
        }
        copyTree(quarkusApp, destApp);
        // Also place quarkus-run.jar at targetDir root for the packager finder.
        Files.copy(runJar, targetDir.resolve("quarkus-run.jar"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("jk-quarkus-augment: " + targetDir.resolve("quarkus-run.jar"));
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

    private static List<RuntimeCoord> discoverExtensions(List<RuntimeCoord> runtime) {
        List<RuntimeCoord> extensions = new ArrayList<>();
        for (RuntimeCoord d : runtime) {
            if (isQuarkusExtension(d.jar())) {
                extensions.add(d);
            }
        }
        if (extensions.isEmpty()) {
            for (RuntimeCoord d : runtime) {
                if (d.group().startsWith("io.quarkus")
                        && !d.artifact().contains("bootstrap")
                        && !d.artifact().endsWith("-spi")
                        && !d.artifact().endsWith("-deployment")) {
                    extensions.add(d);
                }
            }
        }
        // Prefer top-level extensions first.
        Set<String> seen = new LinkedHashSet<>();
        List<RuntimeCoord> ordered = new ArrayList<>();
        for (String want : List.of("quarkus-rest", "quarkus-arc", "quarkus-core")) {
            for (RuntimeCoord d : extensions) {
                if (d.artifact().equals(want) && seen.add(d.group() + ":" + d.artifact())) {
                    ordered.add(d);
                }
            }
        }
        for (RuntimeCoord d : extensions) {
            if (seen.add(d.group() + ":" + d.artifact())) {
                ordered.add(d);
            }
        }
        return ordered.size() > 20 ? ordered.subList(0, 20) : ordered;
    }

    private static boolean isQuarkusExtension(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/quarkus-extension.properties") != null
                    || jf.getEntry("META-INF/quarkus-extension.yaml") != null
                    || jf.getEntry("META-INF/quarkus-extension.yml") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findMaven() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] parts = path.split(java.io.File.pathSeparator);
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        for (String p : parts) {
            Path cand = Path.of(p, name);
            if (Files.isExecutable(cand) || Files.isRegularFile(cand)) return cand;
        }
        // sdkman / common locations
        String home = System.getProperty("user.home");
        for (String rel : List.of(
                ".sdkman/candidates/maven/current/bin/mvn", "apache-maven/bin/mvn", ".mvn/wrapper/maven-wrapper.jar")) {
            Path cand = Path.of(home, rel);
            if (Files.isExecutable(cand)) return cand;
        }
        return null;
    }

    private static String mavenPom(
            String group, String artifact, String version, String quarkusVersion, List<RuntimeCoord> extensions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>").append(xml(group)).append("</groupId>\n");
        sb.append("  <artifactId>").append(xml(artifact)).append("</artifactId>\n");
        sb.append("  <version>").append(xml(version)).append("</version>\n");
        sb.append("  <properties>\n");
        sb.append("    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        sb.append("    <maven.compiler.release>17</maven.compiler.release>\n");
        sb.append("    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>\n");
        sb.append("    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>\n");
        sb.append("    <quarkus.platform.version>").append(xml(quarkusVersion)).append("</quarkus.platform.version>\n");
        sb.append("    <quarkus.package.jar.type>fast-jar</quarkus.package.jar.type>\n");
        sb.append("    <quarkus.analytics.disabled>true</quarkus.analytics.disabled>\n");
        sb.append("  </properties>\n");
        sb.append("  <dependencyManagement>\n");
        sb.append("    <dependencies>\n");
        sb.append("      <dependency>\n");
        sb.append("        <groupId>${quarkus.platform.group-id}</groupId>\n");
        sb.append("        <artifactId>${quarkus.platform.artifact-id}</artifactId>\n");
        sb.append("        <version>${quarkus.platform.version}</version>\n");
        sb.append("        <type>pom</type>\n");
        sb.append("        <scope>import</scope>\n");
        sb.append("      </dependency>\n");
        sb.append("    </dependencies>\n");
        sb.append("  </dependencyManagement>\n");
        sb.append("  <dependencies>\n");
        for (RuntimeCoord d : extensions) {
            sb.append("    <dependency>\n");
            sb.append("      <groupId>").append(xml(d.group())).append("</groupId>\n");
            sb.append("      <artifactId>").append(xml(d.artifact())).append("</artifactId>\n");
            sb.append("    </dependency>\n");
        }
        sb.append("  </dependencies>\n");
        sb.append("  <build>\n");
        sb.append("    <plugins>\n");
        sb.append("      <plugin>\n");
        sb.append("        <groupId>${quarkus.platform.group-id}</groupId>\n");
        sb.append("        <artifactId>quarkus-maven-plugin</artifactId>\n");
        sb.append("        <version>${quarkus.platform.version}</version>\n");
        sb.append("        <extensions>true</extensions>\n");
        sb.append("        <executions>\n");
        sb.append("          <execution>\n");
        sb.append("            <goals>\n");
        sb.append("              <goal>build</goal>\n");
        sb.append("            </goals>\n");
        sb.append("          </execution>\n");
        sb.append("        </executions>\n");
        sb.append("      </plugin>\n");
        sb.append("    </plugins>\n");
        sb.append("  </build>\n");
        sb.append("</project>\n");
        return sb.toString();
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

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private QuarkusAugmentMain() {}
}
