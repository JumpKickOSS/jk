// SPDX-License-Identifier: Apache-2.0
package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Project build logic (Mill line-count analogue). Invoked as:
 * {@code java -cp … demo.LineCountBuild --project <dir> --out <dir>}
 */
public final class LineCountBuild {
    public static void main(String[] args) throws Exception {
        Path project = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if ("--project".equals(args[i]) && i + 1 < args.length) project = Path.of(args[++i]);
            else if ("--out".equals(args[i]) && i + 1 < args.length) out = Path.of(args[++i]);
        }
        if (project == null || out == null) {
            System.err.println("usage: LineCountBuild --project <dir> --out <dir>");
            System.exit(2);
        }
        Path src = project.resolve("src");
        long lines = 0;
        if (Files.isDirectory(src)) {
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path f : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(".java")) {
                        lines += Files.readAllLines(f).size();
                    }
                }
            }
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("line-count.txt"), lines + "\n");
    }
}
