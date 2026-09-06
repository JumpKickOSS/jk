// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import java.nio.file.Path;

/**
 * Where a rule came from: the file and the line of its table header. Root file today; rule packs
 * and per-module files add layers later and share this shape.
 */
public record RuleSource(Path file, int line, Layer layer) {

    public enum Layer {
        ROOT,
        PACK,
        MODULE
    }

    public String render() {
        return file.getFileName() + ":" + line;
    }
}
