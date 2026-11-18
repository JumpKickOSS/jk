// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Fragment;

/** One file's text in, its fragment out. Pure: no I/O, no state between calls. */
@FunctionalInterface
public interface Extractor {

    Fragment extract(String relPath, String text);
}
