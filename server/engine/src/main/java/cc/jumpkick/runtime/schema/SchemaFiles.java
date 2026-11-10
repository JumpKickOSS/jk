// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.schema;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockSchemas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.SchemaEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The schemas a module's {@code [schema-registry]} table pins, laid out for the generator that
 * reads their type: one directory per pinned set under the store's {@code schemas/}, each schema
 * a file named for its subject. The text comes from the store; a schema the store lacks is fetched
 * from the registry again and refused unless it is the pinned bytes.
 */
public final class SchemaFiles {

    /** The extra a consuming step reads the directory under. */
    public static final String EXTRA = "schema-registry";

    /** The plugin that generates code from each schema type. */
    private static final Map<String, String> TYPE_BY_PLUGIN =
            Map.of("avro", "avro", "protobuf", "protobuf", "json-schema", "json");

    /** A laid-out set: its directory and the digest the reading step is keyed by. */
    public record Dir(Path dir, String token) {}

    private SchemaFiles() {}

    /**
     * The directory of the schemas plugin {@code pluginId} reads for the module at {@code
     * moduleDir}, or empty when the plugin reads no schema type or the module pins none of it.
     */
    public static Optional<Dir> forPlugin(String pluginId, JkBuild module, Path moduleDir, Path lockFile, Cas cas)
            throws IOException, InterruptedException {
        String type = TYPE_BY_PLUGIN.get(pluginId);
        if (type == null) return Optional.empty();
        Path lockDir =
                Objects.requireNonNull(lockFile.toAbsolutePath().normalize().getParent(), "lock directory");
        Optional<LockSchemas.Declared> declared = LockSchemas.of(modulePath(lockDir, moduleDir), module);
        if (declared.isEmpty()) return Optional.empty();
        List<SchemaEntry> rows = new ArrayList<>();
        Lockfile lock = LockfileReader.read(lockFile);
        for (SchemaEntry row : LockSchemas.closure(lock.schemas(), declared.get())) {
            if (row.type().equals(type)) rows.add(row);
        }
        if (rows.isEmpty()) return Optional.empty();
        rows.sort(Comparator.comparing(SchemaEntry::key));
        StringBuilder digest = new StringBuilder();
        for (SchemaEntry row : rows)
            digest.append(row.key()).append(' ').append(row.sha256Hex()).append('\n');
        String token = Hashing.sha256Hex(digest.toString().getBytes(StandardCharsets.UTF_8));
        Path dir = cas.root().resolve("schemas").resolve(token.substring(0, 40));
        if (!Files.isRegularFile(dir.resolve(COMPLETE))) {
            // One layer per set at a time, in this engine and across engines on one store; a
            // second asker waits and then finds the set complete.
            Files.createDirectories(dir.getParent());
            FileLocks.Action layIfAbsent = () -> {
                if (Files.isRegularFile(dir.resolve(COMPLETE))) return;
                try {
                    lay(dir, rows, declared.get(), cas);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("laying schemas was interrupted");
                }
            };
            FileLocks.withLock(dir.resolveSibling(dir.getFileName() + ".lock"), layIfAbsent);
        }
        return Optional.of(new Dir(dir.resolve(type), token));
    }

    private static final String COMPLETE = ".complete";

    /** Writes the set beside {@code dir} and moves it in whole, so a reader never sees half of it. */
    private static void lay(Path dir, List<SchemaEntry> rows, LockSchemas.Declared declared, Cas cas)
            throws IOException, InterruptedException {
        // Per process and thread: the lock above serializes layers, and a staging name of their
        // own keeps one that a killed layer left behind from being anyone else's.
        Path staging = dir.resolveSibling(dir.getFileName() + "."
                + ProcessHandle.current().pid() + "-" + Thread.currentThread().threadId() + ".tmp");
        PathUtil.deleteRecursivelyOrThrow(staging);
        for (SchemaEntry row : rows) {
            byte[] text = text(row, declared, cas);
            Path root = staging.resolve(row.type());
            for (String name : paths(row, rows)) {
                if (name.contains("://")) {
                    throw new IOException(
                            row.subject() + ": referenced as " + name + "; jk lays out relative names only");
                }
                Path file = root.resolve(name).normalize();
                if (!file.startsWith(root))
                    throw new IOException(row.subject() + ": reference name " + name + " leaves its directory");
                Files.createDirectories(file.getParent());
                AtomicWrites.replace(file, text);
            }
        }
        AtomicWrites.replace(staging.resolve(COMPLETE), new byte[0]);
        // Only an incomplete directory (a layer killed mid-move) is replaced; a complete one is kept.
        if (Files.isRegularFile(dir.resolve(COMPLETE))) {
            PathUtil.deleteRecursivelyOrThrow(staging);
            return;
        }
        PathUtil.deleteRecursivelyOrThrow(dir);
        try {
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException raced) {
            PathUtil.deleteRecursivelyOrThrow(staging);
        }
    }

    /** The pinned text of {@code row}: from the store, else fetched and checked against the pin. */
    static byte[] text(SchemaEntry row, LockSchemas.Declared declared, Cas cas)
            throws IOException, InterruptedException {
        String sha = row.sha256Hex();
        if (cas.contains(sha)) return cas.read(sha);
        String what = row.subject() + " v" + row.version();
        if (SessionContext.current().offline()) throw new IOException(what + ": not downloaded; run jk sync online");
        byte[] served = RegistryClient.of(declared)
                .version(row.subject(), Integer.toString(row.version()))
                .schema()
                .getBytes(StandardCharsets.UTF_8);
        if (!Hashing.sha256Hex(served).equalsIgnoreCase(sha)) {
            throw new IOException(what + ": registry changed it since jk lock; run jk lock -F");
        }
        cas.put(served, sha);
        return served;
    }

    /**
     * Where {@code row} is laid out: an Avro schema at its subject's file name; a Protobuf or JSON
     * schema also at every name a schema of the set imports it by, since protoc and a {@code $ref}
     * find a file by the path written in the schema.
     */
    static Set<String> paths(SchemaEntry row, List<SchemaEntry> rows) {
        Set<String> out = new LinkedHashSet<>();
        if (!row.type().equals("avro")) {
            for (SchemaEntry other : rows) {
                for (SchemaEntry.Ref ref : other.references()) {
                    if (ref.key().equals(row.key())) out.add(ref.name());
                }
            }
        }
        if (out.isEmpty()) out.add(fileName(row));
        return out;
    }

    /** The subject as a file name, with the type's extension. */
    static String fileName(SchemaEntry row) {
        String base = row.subject().replaceAll("[^A-Za-z0-9._-]", "_");
        return base
                + switch (row.type()) {
                    case "protobuf" -> ".proto";
                    case "json" -> ".json";
                    default -> ".avsc";
                };
    }

    /** Every schema the module pins, put in the store: what {@code jk sync} prefetches, as {@code subject@version}. */
    public static List<String> prefetch(JkBuild module, Path moduleDir, Path lockDir, Lockfile lock, Cas cas)
            throws IOException, InterruptedException {
        Optional<LockSchemas.Declared> declared = LockSchemas.of(modulePath(lockDir, moduleDir), module);
        if (declared.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (SchemaEntry row : LockSchemas.closure(lock.schemas(), declared.get())) {
            text(row, declared.get(), cas);
            out.add(row.key());
        }
        return out;
    }

    /** {@code moduleDir} as a path under the lock's directory; null for the lock's own project. */
    static @Nullable String modulePath(Path lockDir, Path moduleDir) {
        String rel = lockDir.toAbsolutePath()
                .normalize()
                .relativize(moduleDir.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return rel.isEmpty() ? null : rel;
    }
}
