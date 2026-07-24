// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.layout.ModuleLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE/BSP view of {@link ModuleLayout} (JK-1139 / JK-1145). Thin adapter so generators keep a
 * stable type without depending on engine packages.
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
        for (ModuleLayout.Root r : ModuleLayout.roots(moduleDir)) {
            out.add(new Root(r.relative(), map(r.kind())));
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
