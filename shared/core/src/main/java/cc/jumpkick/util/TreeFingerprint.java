// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * Deterministic tree fingerprint: SHA-256 of sorted {@code <relpath>\0<file-sha256>\n} lines.
 * Per-file hashes run on {@link JkThreads#cpu()}; aggregation preserves sorted order.
 */
public final class TreeFingerprint {

    private TreeFingerprint() {}

    public static String compute(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) ->
                root.relativize(a).toString().compareTo(root.relativize(b).toString()));

        // Dispatch a CPU-pool hash for every file; futures keep input order.
        List<CompletableFuture<String>> hashes = new ArrayList<>(files.size());
        for (Path file : files) {
            hashes.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return Hashing.sha256Hex(Files.readAllBytes(file));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    JkThreads.cpu()));
        }

        StringBuilder sb = new StringBuilder(files.size() * 96);
        for (int i = 0; i < files.size(); i++) {
            String relative = root.relativize(files.get(i)).toString();
            String contentHash;
            try {
                contentHash = hashes.get(i).get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof UncheckedIOException uio) throw uio.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("tree fingerprint: hashing failed at " + relative, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("tree fingerprint: interrupted", e);
            }
            sb.append(relative).append('\0').append(contentHash).append('\n');
        }
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
