// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates project build-logic {@code .kts} stem scripts via {@code kotlinc -script} (product
 * Kotlin distribution from {@link CompileToolchain#resolveKotlinHome}).
 *
 * <p>Bindings match the Groovy host (as top-level vals in a generated wrapper):
 *
 * <ul>
 *   <li>{@code projectDir} — {@link Path} project root
 *   <li>{@code outDir} — {@link Path} action-cached task output
 *   <li>{@code classesDir} — {@link Path} module classes tree
 * </ul>
 *
 * <p>No {@code ant} / {@code properties} bag (Kotlin scripts use the JDK; call Ant from a Groovy
 * stem or compiled logic if needed). User {@code import} / {@code @file:} lines are hoisted above
 * the bindings so the script remains a valid Kotlin script.
 */
final class BuildLogicKtsHost {

    private BuildLogicKtsHost() {}

    static void evaluate(Path script, Path projectDir, Path outDir, Path classesDir) throws Exception {
        Path kotlinHome = CompileToolchain.resolveKotlinHome(JkDirs.cache(), null, msg -> {
            /* silent — engine labels surface the task, not toolchain chatter */
        });
        String kotlincName = HostPlatform.isWindows() ? "kotlinc.bat" : "kotlinc";
        Path kotlinc = kotlinHome.resolve("bin").resolve(kotlincName);
        if (!Files.isRegularFile(kotlinc)) {
            throw new IllegalStateException("[build] logic: kotlinc not found at " + kotlinc);
        }

        Path wrapper = Files.createTempFile("jk-build-logic-", ".kts");
        try {
            Files.writeString(wrapper, wrap(script, projectDir, outDir, classesDir), StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>();
            cmd.add(kotlinc.toString());
            // Prefer the engine JVM for the script process (stable vs project pin).
            cmd.add("-J-Djava.home=" + JavaHomes.runningJavaHome());
            cmd.add("-script");
            cmd.add(wrapper.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(projectDir.toFile());
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                String detail = log == null ? "" : log.strip();
                throw new IllegalStateException("[build] logic: kotlinc -script failed (exit "
                        + exit
                        + ")"
                        + (detail.isEmpty() ? "" : ":\n" + detail));
            }
        } finally {
            Files.deleteIfExists(wrapper);
        }
    }

    /** Build a self-contained script: imports + bindings + user body. */
    static String wrap(Path script, Path projectDir, Path outDir, Path classesDir) throws IOException {
        String user = Files.readString(script, StandardCharsets.UTF_8);
        if (user.lines().anyMatch(l -> l.stripLeading().startsWith("package "))) {
            throw new IllegalStateException(
                    "[build] logic: .kts stem scripts must not declare a package (" + script.getFileName() + ")");
        }

        List<String> userImports = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean inHeader = true;
        for (String line : user.split("\n", -1)) {
            String t = line.stripLeading();
            if (inHeader) {
                if (t.isEmpty()) {
                    continue; // skip leading blank lines
                }
                if (t.startsWith("import ") || t.startsWith("@file:")) {
                    userImports.add(line);
                    continue;
                }
                if (t.startsWith("//")) {
                    // leading file comments stay in body after bindings
                    inHeader = false;
                    body.append(line).append('\n');
                    continue;
                }
                inHeader = false;
            }
            body.append(line).append('\n');
        }

        StringBuilder out = new StringBuilder();
        out.append("import java.nio.file.Path\n");
        for (String imp : userImports) {
            // Avoid duplicate Path import from the user script.
            if (imp.strip().equals("import java.nio.file.Path")) continue;
            out.append(imp).append('\n');
        }
        out.append('\n');
        out.append("// Bindings injected by JumpKick build-logic (do not redefine)\n");
        out.append("val projectDir: Path = Path.of(").append(ktString(projectDir.toString())).append(")\n");
        out.append("val outDir: Path = Path.of(").append(ktString(outDir.toString())).append(")\n");
        out.append("val classesDir: Path = Path.of(").append(ktString(classesDir.toString())).append(")\n");
        out.append('\n');
        out.append(body);
        return out.toString();
    }

    /** Kotlin double-quoted string literal. */
    static String ktString(String s) {
        return "\""
                + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                + "\"";
    }
}
