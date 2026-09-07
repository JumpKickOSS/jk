// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE/BSP view of {@link ModuleLayout} Mill-like SIMPLE). Thin adapter so
 * generators keep a stable type without depending on engine packages.
 */
public final class IdeSourceRoots {

    public enum Kind {
        SOURCE,
        TEST,
        RESOURCE,
        TEST_RESOURCE
    }

    public record Root(String relative, Kind kind) {
        public boolean test() {
            return kind == Kind.TEST || kind == Kind.TEST_RESOURCE;
        }

        public boolean resource() {
            return kind == Kind.RESOURCE || kind == Kind.TEST_RESOURCE;
        }
    }

    private IdeSourceRoots() {}

    public static List<Root> of(Path moduleDir) {
        List<Root> out = new ArrayList<>();
        for (ModuleLayout.Root r : ModuleLayout.diskRoots(moduleDir)) {
            out.add(new Root(r.relative(), map(r.kind())));
        }
        // The guard suite is not a test suite, so discovery never lists it; the IDE still wants it as
        // a test root — a guard test needs a debugger like any test.
        boolean compact = ModuleLayout.isCompact(moduleDir);
        for (Path root : TestSuites.javaRoots(moduleDir, compact, TestSuites.GUARD)) {
            if (Files.isDirectory(root)) {
                out.add(new Root(moduleDir.relativize(root).toString().replace('\\', '/'), Kind.TEST));
            }
        }
        return List.copyOf(out);
    }

    public static List<String> discoveredSuites(Path moduleDir) {
        return ModuleLayout.discoveredSuites(moduleDir);
    }

    public static boolean isCompact(Path moduleDir) {
        return ModuleLayout.isCompact(moduleDir);
    }

    private static Kind map(ModuleLayout.Kind k) {
        return switch (k) {
            case SOURCE -> Kind.SOURCE;
            case RESOURCE -> Kind.RESOURCE;
            case TEST -> Kind.TEST;
            case TEST_RESOURCE -> Kind.TEST_RESOURCE;
        };
    }
}
