// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Log4j 2's plugin cache ({@code Log4j2Plugins.dat}), merged across jars. Each jar that ships
 * plugins carries one at the same path, and Log4j reads only the file it finds there, so a fat jar
 * that kept the first lost every other jar's appenders, layouts and lookups. The format is Log4j's
 * {@code PluginCache}: a count of categories, and per category its name, a count of entries and
 * per entry its key, class name, name, printable and defer flags.
 */
final class Log4j2PluginCache {

    static final String PATH = "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat";

    private record Entry(String key, String className, String name, boolean printable, boolean defer) {}

    /** Category to key to entry; the first jar to name a key keeps it, as Log4j's own merge does. */
    private final Map<String, Map<String, Entry>> categories = new TreeMap<>();

    void add(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int categoryCount = in.readInt();
            for (int c = 0; c < categoryCount; c++) {
                Map<String, Entry> entries = categories.computeIfAbsent(in.readUTF(), k -> new LinkedHashMap<>());
                int entryCount = in.readInt();
                for (int e = 0; e < entryCount; e++) {
                    Entry entry =
                            new Entry(in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean(), in.readBoolean());
                    entries.putIfAbsent(entry.key(), entry);
                }
            }
        }
    }

    byte[] render() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(categories.size());
            for (Map.Entry<String, Map<String, Entry>> category : categories.entrySet()) {
                out.writeUTF(category.getKey());
                out.writeInt(category.getValue().size());
                for (Entry e : new TreeMap<>(category.getValue()).values()) {
                    out.writeUTF(e.key());
                    out.writeUTF(e.className());
                    out.writeUTF(e.name());
                    out.writeBoolean(e.printable());
                    out.writeBoolean(e.defer());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("writing to memory", e);
        }
        return buf.toByteArray();
    }
}
