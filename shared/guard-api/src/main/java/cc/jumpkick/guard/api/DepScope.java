// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/** A manifest dependency scope, by its {@code jk.toml} table name. */
public enum DepScope {
    MAIN("dependencies"),
    EXPORT("export-dependencies"),
    PROVIDED("provided-dependencies"),
    RUNTIME("runtime-dependencies"),
    TEST("test-dependencies"),
    PROCESSOR("processor-dependencies"),
    PLATFORM("platform-dependencies"),
    DEV("dev-dependencies");

    private final String table;

    DepScope(String table) {
        this.table = table;
    }

    /** The {@code jk.toml} table, e.g. {@code test-dependencies}. */
    public String table() {
        return table;
    }
}
