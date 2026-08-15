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
 * </ul>
 *
 * <p>No {@code ant} / {@code properties} bag (Kotlin scripts use the JDK; call Ant from a Groovy
 * stem or compiled logic if needed). User {@code import} / {@code @file:} lines are hoisted above
 * the bindings so the script remains a valid Kotlin script.
 */
final class BuildLogicKtsHost {

    private BuildLogicKtsHost() {}

    static void evaluate(Path script, Path projectDir, Path outDir) throws Exception {
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
            Files.writeString(wrapper, wrap(script, projectDir, outDir), StandardCharsets.UTF_8);
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
    static String wrap(Path script, Path projectDir, Path outDir) throws IOException {
        String user = Files.readString(script, StandardCharsets.UTF_8);
        if (user.lines().anyMatch(l -> l.stripLeading().startsWith("package "))) {
            throw new IllegalStateException(
                    "[build] logic: .kts stem scripts must not declare a package (" + script.getFileName() + ")");
        }

        // Kotlin's file order is fixed: @file: annotations, then imports, then declarations. The
        // injected bindings are declarations, so every user import has to be hoisted above them —
        // including imports that sit after a comment. A comment must not end the header scan: this
        // repo's own convention puts `// SPDX-License-Identifier` on line 1, which would have
        // pushed every following import below the bindings and made the script uncompilable
        // — and a `/* ... */` block comment (a common license-header style) is the same
        // bug for a comment that can also span multiple lines.
        List<String> fileAnnotations = new ArrayList<>();
        List<String> userImports = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean inHeader = true;
        boolean inBlockComment = false;
        for (String line : user.split("\n", -1)) {
            if (inHeader) {
                String remaining = line.stripLeading();
                boolean consumedByComment = false;
                while (true) {
                    if (inBlockComment) {
                        int end = remaining.indexOf("*/");
                        if (end < 0) {
                            consumedByComment = true;
                            break;
                        }
                        inBlockComment = false;
                        remaining = remaining.substring(end + 2).stripLeading();
                        if (remaining.isEmpty()) {
                            consumedByComment = true;
                            break;
                        }
                        continue;
                    }
                    if (remaining.isEmpty() || remaining.startsWith("//")) {
                        consumedByComment = true;
                        break;
                    }
                    if (remaining.startsWith("/*")) {
                        int end = remaining.indexOf("*/", 2);
                        if (end < 0) {
                            inBlockComment = true;
                            consumedByComment = true;
                            break;
                        }
                        remaining = remaining.substring(end + 2).stripLeading();
                        if (remaining.isEmpty()) {
                            consumedByComment = true;
                            break;
                        }
                        continue;
                    }
                    break; // remaining now holds real header content: @file:, import, or code
                }
                if (consumedByComment) continue; // blank/comment-only line never ends the header
                if (remaining.startsWith("@file:")) {
                    fileAnnotations.add(line);
                    continue;
                }
                if (remaining.startsWith("import ")) {
                    userImports.add(line);
                    continue;
                }
                inHeader = false;
            }
            body.append(line).append('\n');
        }

        StringBuilder out = new StringBuilder();
        // @file: annotations must precede every import, including the injected one.
        for (String ann : fileAnnotations) {
            out.append(ann).append('\n');
        }
        out.append("import java.nio.file.Path\n");
        for (String imp : userImports) {
            // Avoid duplicate Path import from the user script.
            if (imp.strip().equals("import java.nio.file.Path")) continue;
            out.append(imp).append('\n');
        }
        out.append('\n');
        out.append("// Bindings injected by JumpKick build-logic (do not redefine)\n");
        out.append("val projectDir: Path = Path.of(")
                .append(ktString(projectDir.toString()))
                .append(")\n");
        out.append("val outDir: Path = Path.of(")
                .append(ktString(outDir.toString()))
                .append(")\n");
        out.append('\n');
        out.append(body);
        return out.toString();
    }

    /** Kotlin double-quoted string literal. */
    static String ktString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
    }
}
