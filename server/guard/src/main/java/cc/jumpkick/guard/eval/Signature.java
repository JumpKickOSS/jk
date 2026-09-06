// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.Descriptors;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;

/**
 * One {@code forbid} signature, in forbidden-apis' grammar:
 *
 * <pre>
 * pkg.Class                       the type: any reference, call or field access on it or a subtype
 * pkg.Class#method(a.B,int)       a method with exactly these parameter types
 * pkg.Class#method(**)            any overload
 * pkg.Class#name                  a method of that name (any overload) or a field of that name
 * pkg.Class#FIELD                 a field (UPPER_CASE name)
 * pkg.Class#&lt;init&gt;(**)            any constructor
 * pkg.**                          every type in the package and its subpackages
 * pkg.*                           every type in the package
 * </pre>
 *
 * A member signature matches after hierarchy resolution: a call whose owner is a subtype of the
 * named class matches, so a ban on {@code List#add} catches {@code ArrayList#add}.
 */
public record Signature(
        String raw,
        Kind kind,
        String owner,
        @Nullable String member,
        @Nullable List<String> params) {

    public enum Kind {
        TYPE,
        PACKAGE,
        PACKAGE_TREE,
        METHOD,
        FIELD,
        /** {@code pkg.Class#name} without parentheses and not UPPER_CASE: a method of that name or a field. */
        MEMBER
    }

    private static final Pattern MEMBER = Pattern.compile("^([\\w.$]+)#([\\w$<>]+)(?:\\((.*)\\))?$");
    private static final Pattern TYPE = Pattern.compile("^[\\w.$]+$");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Z_][A-Z0-9_$]*");

    public static Signature parse(String raw) {
        String s = raw.strip();
        if (s.endsWith(".**"))
            return new Signature(
                    s, Kind.PACKAGE_TREE, Descriptors.internalName(s.substring(0, s.length() - 3)), null, null);
        if (s.endsWith(".*"))
            return new Signature(s, Kind.PACKAGE, Descriptors.internalName(s.substring(0, s.length() - 2)), null, null);
        Matcher m = MEMBER.matcher(s);
        if (m.matches()) {
            String owner = Descriptors.internalName(m.group(1));
            String member = m.group(2);
            String paramText = m.group(3);
            if (paramText == null) {
                boolean field = FIELD_NAME.matcher(member).matches();
                return new Signature(s, field ? Kind.FIELD : Kind.MEMBER, owner, member, null);
            }
            List<String> params = null;
            if (paramText != null && !paramText.strip().equals("**")) {
                params = new ArrayList<>();
                if (!paramText.isBlank()) for (String p : paramText.split(",")) params.add(p.strip());
            }
            return new Signature(s, Kind.METHOD, owner, member, params);
        }
        if (TYPE.matcher(s).matches()) return new Signature(s, Kind.TYPE, Descriptors.internalName(s), null, null);
        throw new IllegalArgumentException("not a signature: `" + raw
                + "` — expected pkg.Class, pkg.Class#member(args), pkg.Class#FIELD or pkg.**");
    }

    /** Whether the owner side matches {@code ownerInternal}, given its ancestors (itself first). */
    public boolean ownerMatches(String ownerInternal, Iterable<String> ancestors) {
        switch (kind) {
            case PACKAGE -> {
                return Descriptors.packageOf(ownerInternal).equals(Descriptors.binaryName(owner));
            }
            case PACKAGE_TREE -> {
                String pkg = Descriptors.packageOf(ownerInternal);
                String want = Descriptors.binaryName(owner);
                return pkg.equals(want) || pkg.startsWith(want + ".");
            }
            default -> {
                for (String a : ancestors) if (a.equals(owner)) return true;
                return false;
            }
        }
    }

    /** Whether a method with this name and descriptor matches (owner already matched). */
    public boolean methodMatches(String name, String desc) {
        if ((kind != Kind.METHOD && kind != Kind.MEMBER) || member == null || !member.equals(name)) return false;
        if (params == null) return true;
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length != params.size()) return false;
        for (int i = 0; i < args.length; i++) {
            if (!typeNameEquals(args[i], params.get(i))) return false;
        }
        return true;
    }

    public boolean fieldMatches(String name) {
        return (kind == Kind.FIELD || kind == Kind.MEMBER) && member != null && member.equals(name);
    }

    /** The class this signature needs to resolve at load: its owner, when it names one. */
    public @Nullable String ownerToResolve() {
        return kind == Kind.PACKAGE || kind == Kind.PACKAGE_TREE ? null : owner;
    }

    private static boolean typeNameEquals(Type t, String source) {
        String want = source.replace(" ", "");
        String have = t.getClassName();
        if (have.equals(want)) return true;
        // Allow the simple name for java.lang types: `String` for `java.lang.String`.
        return have.startsWith("java.lang.")
                && have.substring("java.lang.".length()).equals(want);
    }

    @Override
    public String toString() {
        return raw;
    }
}
