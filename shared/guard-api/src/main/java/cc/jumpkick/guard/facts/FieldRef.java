// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

/** One field access instruction, deduplicated per {@code (origin member, target)} like a call. */
public record FieldRef(String owner, String name, String desc, int line, boolean write, int count) {

    public String target() {
        return Descriptors.binaryName(owner) + "#" + name;
    }
}
