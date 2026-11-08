// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.util.MinimalToml;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One {@code [[schema]]} row: a schema a module's {@code [schema-registry]} table downloads, pinned
 * as the registry served it at lock time. {@code checksum} ({@code sha256:<hex>}) is the digest of
 * the schema text; {@code references} are the subject versions the schema refers to, each a row of
 * its own, under the name the schema uses for it: an Avro type's full name, a Protobuf import path,
 * a JSON Schema {@code $ref}.
 *
 * @param module the module path under the lock, or null for the lock's own project
 * @param registry the registry's base URL
 * @param type the schema type, lower case: {@code avro}, {@code protobuf} or {@code json}
 */
public record SchemaEntry(
        @Nullable String module,
        String registry,
        String subject,
        int version,
        long id,
        String type,
        String checksum,
        List<Ref> references) {

    /** A reference: the name the schema refers to it by, and the subject version it is. */
    public record Ref(String name, String subject, int version) {

        /** {@code <subject>@<version>}: the row it is. */
        public String key() {
            return subject + "@" + version;
        }
    }

    public SchemaEntry {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(checksum, "checksum");
        type = type.toLowerCase(Locale.ROOT);
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** {@code <subject>@<version>}: how a reference names this row. */
    public String key() {
        return subject + "@" + version;
    }

    /** The hex digest of the schema text. */
    public String sha256Hex() {
        return checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
    }

    /** Whether this row answers for the module at {@code modulePath} (null: the lock's own project). */
    public boolean answersFor(@Nullable String modulePath) {
        return Objects.equals(module, modulePath);
    }

    static final Comparator<SchemaEntry> ORDER = Comparator.comparing(
                    (SchemaEntry s) -> s.module == null ? "" : s.module)
            .thenComparing(SchemaEntry::subject)
            .thenComparingInt(SchemaEntry::version);

    /** The rows under {@code [[schema]]}; empty when the lock has none. */
    static List<SchemaEntry> read(LockToml lock) {
        List<SchemaEntry> out = new ArrayList<>();
        LockToml.Array array = lock.getArray("schema");
        if (array == null) return out;
        for (int i = 0; i < array.size(); i++) {
            LockToml t = array.getTable(i);
            List<Ref> refs = new ArrayList<>();
            LockToml.Array refArray = t.getArray("references");
            if (refArray != null) {
                for (int j = 0; j < refArray.size(); j++) {
                    LockToml ref = refArray.getTable(j);
                    refs.add(new Ref(
                            require(ref, "name"),
                            require(ref, "subject"),
                            Math.toIntExact(requireLong(ref, "version"))));
                }
            }
            out.add(new SchemaEntry(
                    t.getString("module"),
                    require(t, "registry"),
                    require(t, "subject"),
                    Math.toIntExact(requireLong(t, "version")),
                    requireLong(t, "id"),
                    require(t, "type"),
                    require(t, "checksum"),
                    refs));
        }
        return out;
    }

    /** One {@code [[schema]]} row per entry, sorted by module, subject and version. */
    static void write(StringBuilder out, List<SchemaEntry> schemas) {
        List<SchemaEntry> sorted = new ArrayList<>(schemas);
        sorted.sort(ORDER);
        for (SchemaEntry s : sorted) {
            out.append("\n[[schema]]\n");
            if (s.module != null)
                out.append("module   = ").append(quote(s.module)).append('\n');
            out.append("registry = ").append(quote(s.registry)).append('\n');
            out.append("subject  = ").append(quote(s.subject)).append('\n');
            out.append("version  = ").append(s.version).append('\n');
            out.append("id       = ").append(s.id).append('\n');
            out.append("type     = ").append(quote(s.type)).append('\n');
            out.append("checksum = ").append(quote(s.checksum)).append('\n');
            if (!s.references.isEmpty()) {
                out.append("references = [");
                for (int i = 0; i < s.references.size(); i++) {
                    Ref ref = s.references.get(i);
                    out.append(i > 0 ? ", " : "")
                            .append("{ name = ")
                            .append(quote(ref.name()))
                            .append(", subject = ")
                            .append(quote(ref.subject()))
                            .append(", version = ")
                            .append(ref.version())
                            .append(" }");
                }
                out.append("]\n");
            }
        }
    }

    private static String quote(String value) {
        return MinimalToml.quote(value);
    }

    private static String require(LockToml t, String key) {
        String value = t.getString(key);
        if (value == null) throw new IllegalArgumentException("[[schema]] is missing required key `" + key + "`");
        return value;
    }

    private static long requireLong(LockToml t, String key) {
        Long value = t.getLong(key);
        if (value == null) throw new IllegalArgumentException("[[schema]] is missing required key `" + key + "`");
        return value;
    }
}
