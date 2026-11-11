// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.asyncapi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * protoc over the Protobuf payloads: the binary staged executable, the well-known types unpacked
 * from the protobuf-java jar as an include root, the Java written into the step's output.
 */
final class Protoc {

    private static final String WELL_KNOWN = "google/protobuf/";

    private Protoc() {}

    /** {@code tools} is scratch for the staged binary and the include root; the caller removes it. */
    static void run(Path protoc, Path protobufJava, Path root, List<String> files, Path out, Path tools)
            throws IOException, InterruptedException {
        boolean windows = File.separatorChar == '\\';
        Path staged = tools.resolve(windows ? "protoc.exe" : "protoc");
        Files.copy(protoc, staged, StandardCopyOption.REPLACE_EXISTING);
        if (!staged.toFile().setExecutable(true) && !windows) throw new IOException("cannot mark protoc executable");
        Path include = tools.resolve("include");
        unpackWellKnown(protobufJava, include);
        List<String> command = new ArrayList<>(
                List.of(staged.toString(), "--java_out=" + out, "-I", root.toString(), "-I", include.toString()));
        command.addAll(files);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (!output.isBlank()) System.err.print(output);
        if (exit != 0) throw new IllegalStateException("protoc failed (exit " + exit + ")");
    }

    /** The {@code google/protobuf/*.proto} files inside {@code jar}, written under {@code include}. */
    private static void unpackWellKnown(Path jar, Path include) throws IOException {
        try (InputStream in = Files.newInputStream(jar);
                ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(WELL_KNOWN) || !name.endsWith(".proto")) continue;
                Path file = include.resolve(name).normalize();
                if (!file.startsWith(include)) continue;
                Files.createDirectories(file.getParent());
                Files.write(file, zip.readAllBytes());
            }
        }
    }
}
