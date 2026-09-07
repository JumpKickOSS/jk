// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.Descriptors;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A member signature to look for: {@code owner#name(params)}. {@code owner} is a binary class name
 * where {@code *} matches within one segment and {@code **} across segments; {@code name} is a
 * method name or {@code *}; {@code (**)} is any parameter list, {@code ()} none, and a
 * comma-separated list of type names ({@code java.lang.String, int, byte[]}) is exact. Without a
 * parameter list every overload matches.
 */
public final class Sig {

    private final Pattern owner;
    private final String name;
    private final @Nullable List<String> params; // null → any

    private Sig(Pattern owner, String name, @Nullable List<String> params) {
        this.owner = owner;
        this.name = name;
        this.params = params;
    }

    public static Sig of(String spec) {
        String s = spec.strip();
        int hash = s.indexOf('#');
        if (hash < 0) throw new IllegalArgumentException("a signature is owner#name(params): " + spec);
        String owner = s.substring(0, hash).strip();
        String rest = s.substring(hash + 1).strip();
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren).strip();
        @Nullable List<String> params = null;
        if (paren >= 0) {
            if (!rest.endsWith(")")) throw new IllegalArgumentException("unbalanced parameter list: " + spec);
            String inner = rest.substring(paren + 1, rest.length() - 1).strip();
            if (!inner.equals("**")) {
                params = new ArrayList<>();
                if (!inner.isEmpty()) for (String p : inner.split(",")) params.add(p.strip());
            }
        }
        if (owner.isEmpty() || name.isEmpty())
            throw new IllegalArgumentException("a signature is owner#name(params): " + spec);
        return new Sig(glob(owner), name, params == null ? null : List.copyOf(params));
    }

    /** Whether an invocation of {@code owner.name(desc)} (internal owner name, JVM descriptor) is this signature. */
    public boolean matches(String ownerInternal, String memberName, String desc) {
        if (!name.equals("*") && !name.equals(memberName)) return false;
        if (!owner.matcher(Descriptors.binaryName(ownerInternal)).matches()) return false;
        if (params == null) return true;
        return params.equals(parameterTypes(desc));
    }

    /** Parameter type names of a method descriptor, as written in source ({@code java.lang.String}, {@code int[]}). */
    static List<String> parameterTypes(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
            i++;
            out.add(sourceName(desc.substring(start, i)));
        }
        return out;
    }

    /** A field descriptor as source spells it: {@code [B} → {@code byte[]}, {@code Ljava/lang/String;} → {@code java.lang.String}. */
    static String sourceName(String desc) {
        int dims = 0;
        while (desc.charAt(dims) == '[') dims++;
        String base = desc.substring(dims);
        String name =
                switch (base.charAt(0)) {
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'D' -> "double";
                    case 'F' -> "float";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'S' -> "short";
                    case 'Z' -> "boolean";
                    case 'V' -> "void";
                    default -> Descriptors.binaryName(base.substring(1, base.length() - 1));
                };
        return name + "[]".repeat(dims);
    }

    private static Pattern glob(String owner) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < owner.length(); i++) {
            char c = owner.charAt(i);
            if (c == '*') {
                if (i + 1 < owner.length() && owner.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^.]*");
                }
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    @Override
    public String toString() {
        return owner.pattern() + "#" + name + (params == null ? "(**)" : params.toString());
    }
}
