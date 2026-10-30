// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A release downloaded, verified and in the store CAS, not yet installed: {@code jk-update.toml}
 * beside the engine pointer. {@code jk self update --download} and the engine's own update write
 * it; installing it (or finding it no newer than the install) deletes it.
 *
 * <pre>
 * version = "0.17.0"
 * from = "0.16.2"
 * engine-sha256 = "…"
 * client = "native"
 * client-sha256 = "…"
 * prepared-at = "2026-10-30T14:02:11Z"
 * </pre>
 *
 * @param version the release this installs
 * @param from the version that downloaded it
 * @param engineSha256 the CAS blob of {@code jk-engine-<version>.jar}
 * @param client which client the blob is: the host's native binary or the JVM client jar
 * @param clientSha256 the CAS blob of that client
 * @param preparedAt when the download finished
 */
public record PreparedUpdate(
        String version, String from, String engineSha256, Client client, String clientSha256, Instant preparedAt) {

    public static final String FILE_NAME = "jk-update.toml";

    /** The client a prepared update installs. */
    public enum Client {
        NATIVE,
        JVM;

        String spelling() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The record under {@code engineHome}; may not exist. */
    public static Path file(Path engineHome) {
        return engineHome.resolve(FILE_NAME);
    }

    /** The record under {@code engineHome}, or empty when there is none or it does not parse. */
    public static Optional<PreparedUpdate> read(Path engineHome) {
        Path file = file(engineHome);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            Map<String, String> v = MinimalToml.readFlat(AtomicWrites.readString(file));
            return Optional.of(new PreparedUpdate(
                    require(v, "version"),
                    require(v, "from"),
                    require(v, "engine-sha256"),
                    Client.valueOf(require(v, "client").toUpperCase(Locale.ROOT)),
                    require(v, "client-sha256"),
                    Instant.parse(require(v, "prepared-at"))));
        } catch (IOException | IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Write this record under {@code engineHome}, replacing any earlier one. */
    public void write(Path engineHome) throws IOException {
        Files.createDirectories(engineHome);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("version", version);
        v.put("from", from);
        v.put("engine-sha256", engineSha256);
        v.put("client", client.spelling());
        v.put("client-sha256", clientSha256);
        v.put("prepared-at", preparedAt.toString());
        AtomicWrites.replace(file(engineHome), MinimalToml.writeFlat(v));
    }

    /** Remove the record under {@code engineHome}, if any. */
    public static void delete(Path engineHome) throws IOException {
        Files.deleteIfExists(file(engineHome));
    }

    /** Whether this installs something newer than {@code running}. */
    public boolean newerThan(String running) {
        return Versions.compare(version, running) > 0;
    }

    /**
     * Whether both blobs are still in {@code cas} with the bytes the record names. A store wipe or
     * a GC between download and install leaves a record that can only be fetched again.
     */
    public boolean blobsIntact(Cas cas) {
        return intact(cas, engineSha256) && intact(cas, clientSha256);
    }

    private static boolean intact(Cas cas, String sha) {
        Path blob = cas.pathFor(sha);
        if (!Files.isRegularFile(blob)) return false;
        try {
            return Hashing.sha256Hex(blob).equalsIgnoreCase(sha);
        } catch (IOException e) {
            return false;
        }
    }

    private static String require(Map<String, String> values, String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("jk-update.toml has no " + key);
        return v;
    }
}
