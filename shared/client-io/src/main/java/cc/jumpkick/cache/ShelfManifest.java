// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The first-party shelf an install pinned to an engine: {@code jk-shelf.toml} beside the engine
 * pointer, naming the engine by its jar's sha256 and every {@code repos/jk-local} jar the install
 * shelved, by coordinate and sha256. The engine it names launches its workers by these shas: the
 * shelf path is a hint, and a shelf jar another install replaced is served from the artifact CAS.
 *
 * <pre>
 * engine-sha256 = "…"
 * source = "/home/me/src/jk"
 * installed-at = "2026-09-20T12:00:00Z"
 *
 * [jars]
 * "cc.jumpkick:jk-java-compiler:0.13.4" = "…"
 * </pre>
 *
 * @param engineSha256 the sha256 of the engine jar whose workers these are, lower-case hex
 * @param source the checkout (or dist directory) the shelf was installed from
 * @param installedAt when the manifest was written
 * @param jars sha256 per {@code group:artifact:version}, lower-case hex
 */
public record ShelfManifest(String engineSha256, String source, Instant installedAt, Map<String, String> jars) {

    public static final String FILE_NAME = "jk-shelf.toml";

    private static final Pattern ENTRY =
            Pattern.compile("^\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[A-Za-z0-9._-]+)\\s*=\\s*(.+?)\\s*$");

    /** A plain {@code group:artifact:version}: three non-empty segments, no whitespace or control characters. */
    private static final Pattern COORDINATE =
            Pattern.compile("[^\\s:\\p{Cntrl}]+:[^\\s:\\p{Cntrl}]+:[^\\s:\\p{Cntrl}]+");

    public ShelfManifest {
        Objects.requireNonNull(engineSha256, "engineSha256");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(installedAt, "installedAt");
        engineSha256 = engineSha256.strip().toLowerCase(Locale.ROOT);
        Map<String, String> lowered = new TreeMap<>();
        for (var e : jars.entrySet()) {
            if (!isCoordinate(e.getKey())) {
                throw new IllegalArgumentException(
                        "shelf pin key is not a plain group:artifact:version: " + MinimalToml.quote(e.getKey()));
            }
            lowered.put(e.getKey(), e.getValue().strip().toLowerCase(Locale.ROOT));
        }
        jars = Map.copyOf(lowered);
    }

    /** True when {@code key} is a plain {@code group:artifact:version} — no colour escapes, no whitespace. */
    public static boolean isCoordinate(@Nullable String key) {
        return key != null && COORDINATE.matcher(key).matches();
    }

    /** True when this manifest pins the engine whose jar hashes to {@code engineSha256}. */
    public boolean pins(@Nullable String engineSha256) {
        return engineSha256 != null && this.engineSha256.equalsIgnoreCase(engineSha256.strip());
    }

    /** The pinned sha256 of {@code coordinate} ({@code group:artifact:version}), if the install shelved it. */
    public Optional<String> sha(String coordinate) {
        return Optional.ofNullable(jars.get(coordinate));
    }

    /**
     * Record {@code jars} as the shelf of engine {@code engineSha256} in {@code file}: over the
     * entries a manifest already there holds for the same engine, so a scoped install keeps the
     * pins it did not touch; replacing the file outright when it named another engine. The
     * read-merge-write runs under {@code <file>.lock}, so two installs for one engine keep each
     * other's pins.
     */
    public static ShelfManifest record(
            Path file, String engineSha256, Path source, Map<String, String> jars, Clock clock) throws IOException {
        return FileLocks.withLock(lockFile(file), () -> {
            Map<String, String> merged = new LinkedHashMap<>();
            read(file).filter(m -> m.pins(engineSha256)).ifPresent(m -> merged.putAll(m.jars()));
            merged.putAll(jars);
            ShelfManifest manifest = new ShelfManifest(
                    engineSha256, source.toAbsolutePath().normalize().toString(), clock.instant(), merged);
            manifest.write(file);
            return manifest;
        });
    }

    /** The lock every writer of {@code file} takes: {@code <file>.lock} beside it. */
    static Path lockFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".lock");
    }

    /** The manifest at {@code file}; empty when it is absent or does not name an engine. */
    public static Optional<ShelfManifest> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return Optional.empty();
        }
        return parse(lines);
    }

    static Optional<ShelfManifest> parse(List<String> lines) {
        String engine = null;
        String source = "";
        Instant installedAt = Instant.EPOCH;
        Map<String, String> jars = new LinkedHashMap<>();
        boolean inJars = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                inJars = line.equals("[jars]");
                continue;
            }
            Matcher m = ENTRY.matcher(line);
            if (!m.matches()) continue;
            String key = MinimalToml.unquote(m.group(1));
            String value = MinimalToml.unquote(m.group(2));
            if (inJars) {
                if (isCoordinate(key) && Hashing.isHex(value, 64)) jars.put(key, value);
                continue;
            }
            switch (key) {
                case "engine-sha256" -> engine = value;
                case "source" -> source = value;
                case "installed-at" -> installedAt = parseInstant(value);
                default -> {
                    // Unknown top-level key: not this reader's to refuse.
                }
            }
        }
        if (engine == null || !Hashing.isHex(engine, 64)) return Optional.empty();
        return Optional.of(new ShelfManifest(engine, source, installedAt, jars));
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            return Instant.EPOCH;
        }
    }

    /** Write durably: a torn manifest would pin the engine to nothing. */
    public void write(Path file) throws IOException {
        Files.createDirectories(Objects.requireNonNull(file.getParent(), "manifest dir"));
        AtomicWrites.replaceDurably(file, render());
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("engine-sha256 = ").append(MinimalToml.quote(engineSha256)).append('\n');
        sb.append("source = ").append(MinimalToml.quote(source)).append('\n');
        sb.append("installed-at = ")
                .append(MinimalToml.quote(installedAt.toString()))
                .append('\n');
        sb.append('\n').append("[jars]").append('\n');
        for (var e : jars.entrySet()) {
            sb.append(MinimalToml.quote(e.getKey()))
                    .append(" = ")
                    .append(MinimalToml.quote(e.getValue()))
                    .append('\n');
        }
        return sb.toString();
    }
}
