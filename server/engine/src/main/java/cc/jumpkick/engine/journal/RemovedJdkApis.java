// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * APIs the JDK removed after 8, by package or type, with the release that dropped them and the last
 * LTS that still carries them. A compile for a newer release that misses one of these is not a
 * missing library: the module — or a class it compiles against — still needs the API, and the
 * toolchain JDK cross-compiles for the release that has it.
 */
@NullMarked
final class RemovedJdkApis {

    /** One removed API: the package or type prefix, the JDK that removed it, and the last LTS carrying it. */
    record Removal(String prefix, int removedIn, int lastLts) {

        /** The repair row for {@code api}, a package or type under {@link #prefix}. */
        String hint(String api) {
            return "`" + api + "` left the JDK in " + removedIn
                    + " and this module compiles for a newer release: a class it compiles against still needs it — write `java = "
                    + lastLts
                    + "` in this module, the last LTS that carries it (the toolchain JDK cross-compiles with `--release "
                    + lastLts + "`), or move off the API.";
        }
    }

    private static final List<Removal> REMOVALS = List.of(
            new Removal("java.security.acl", 14, 11),
            new Removal("java.util.jar.Pack200", 14, 11),
            new Removal("java.util.jar.Pack200$", 14, 11),
            new Removal("jdk.nashorn", 15, 11),
            new Removal("java.rmi.activation", 17, 11),
            new Removal("java.lang.Compiler", 21, 17),
            new Removal("java.applet", 26, 21));

    private RemovedJdkApis() {}

    /** The removal {@code api} — a package or type name — falls under, or {@code null}. */
    static @Nullable Removal of(String api) {
        for (Removal r : REMOVALS) {
            if (api.equals(r.prefix()) || api.startsWith(r.prefix() + ".") || api.startsWith(r.prefix() + "$")) {
                return r;
            }
        }
        return null;
    }
}
