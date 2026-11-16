// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

/** Why a rename changed nothing: refused before any edit, its message the answer. */
final class RenameRefusal extends RuntimeException {
    RenameRefusal(String message) {
        super(message, null, false, false);
    }
}
