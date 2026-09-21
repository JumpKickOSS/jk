// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * The {@code [application] relocate} rules applied to an assembly: every class whose name starts
 * with a source package is rewritten to the shaded package — in its own name, in every reference
 * any class makes to it, in a string constant that spells it (what {@code Class.forName} reads)
 * — and every entry path and {@code META-INF/services} file under the package moves with it.
 *
 * <p>A rule matches whole package segments: {@code org.apache.lucene} moves {@code
 * org.apache.lucene.index.IndexReader} and not {@code org.apache.lucenex.Other}. The first rule
 * whose source package matches wins, in declaration order.
 */
final class Relocations {

    /** No rules — every entry keeps its name and its bytes. */
    static final Relocations NONE = new Relocations(Map.of());

    private static final String SERVICES = "META-INF/services/";

    /** Dotted source package to dotted shaded package, declaration order. */
    private final Map<String, String> dotted;

    /** The same rules as internal-name prefixes ({@code org/apache/lucene}). */
    private final Map<String, String> slashed;

    private final Remapper remapper = new Remapper(Opcodes.ASM9) {
        @Override
        public String map(String internalName) {
            return relocateSlashed(internalName);
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String text) return relocateConstant(text);
            return super.mapValue(value);
        }
    };

    Relocations(Map<String, String> rules) {
        Objects.requireNonNull(rules, "rules");
        Map<String, String> byDots = new LinkedHashMap<>();
        Map<String, String> bySlashes = new LinkedHashMap<>();
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            byDots.put(rule.getKey(), rule.getValue());
            bySlashes.put(rule.getKey().replace('.', '/'), rule.getValue().replace('.', '/'));
        }
        this.dotted = Collections.unmodifiableMap(byDots);
        this.slashed = Collections.unmodifiableMap(bySlashes);
    }

    boolean isEmpty() {
        return dotted.isEmpty();
    }

    /** {@code name} as the jar carries it: a class, resource or service file under a source package moves. */
    String relocateEntry(String name) {
        if (isEmpty()) return name;
        if (name.startsWith(SERVICES)) {
            return SERVICES + relocateDotted(name.substring(SERVICES.length()));
        }
        return relocateSlashed(name);
    }

    /** Class bytes with every name under a source package rewritten to its shaded package. */
    byte[] relocateClass(byte[] bytes) {
        if (isEmpty()) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    /** A service file's provider lines, each under its shaded package when a rule names it. */
    String relocateServices(String text) {
        if (isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            out.append(relocateDotted(line)).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    /** A string constant spelling a relocated class or package, by dots or by slashes. */
    private String relocateConstant(String text) {
        String byDots = relocateDotted(text);
        if (!byDots.equals(text)) return byDots;
        return relocateSlashed(text);
    }

    private String relocateDotted(String name) {
        return relocate(name, dotted, '.');
    }

    private String relocateSlashed(String name) {
        return relocate(name, slashed, '/');
    }

    /** {@code name} under the first rule whose prefix it carries as whole segments, else itself. */
    private static String relocate(String name, Map<String, String> rules, char separator) {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String from = rule.getKey();
            if (name.equals(from)) return rule.getValue();
            if (name.startsWith(from) && name.length() > from.length() && name.charAt(from.length()) == separator) {
                return rule.getValue() + name.substring(from.length());
            }
        }
        return name;
    }
}
