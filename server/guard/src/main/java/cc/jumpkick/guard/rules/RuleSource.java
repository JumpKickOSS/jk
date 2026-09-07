// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import java.nio.file.Path;

/**
 * Where a rule came from: the file and the line of its table header. Root file today; rule packs
 * and per-module files add layers later and share this shape.
 */
public record RuleSource(Path file, int line, Layer layer, String origin) {

    /** A root-layer source, or one whose origin the caller does not name. */
    public RuleSource(Path file, int line, Layer layer) {
        this(file, line, layer, "");
    }

    public enum Layer {
        ROOT,
        PACK,
        MODULE
    }

    /** The source as a message names it: {@code jk-guards.toml:12}, {@code core/jk-guards.toml:3}, {@code pack g:a:v jk-guards.toml:7}. */
    public String render() {
        String name = file.getFileName() + ":" + line;
        return switch (layer) {
            case ROOT -> name;
            case MODULE -> origin.isEmpty() ? name : origin + "/" + name;
            case PACK -> origin.isEmpty() ? name : "pack " + origin + " " + name;
        };
    }

    /** The layer as the catalog groups it. */
    public String layerLabel() {
        return switch (layer) {
            case ROOT -> GuardsPresence.RULES_FILE;
            case MODULE -> origin.isEmpty() ? "module" : origin + "/" + GuardsPresence.RULES_FILE;
            case PACK -> origin.isEmpty() ? "pack" : "pack " + origin;
        };
    }
}
