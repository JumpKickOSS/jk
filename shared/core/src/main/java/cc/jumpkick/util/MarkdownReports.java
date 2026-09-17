// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The markdown reports jk writes for a person to open — {@code jk-results.md}, the affected-tests
 * ranking, the {@code jk import} report — written UTF-8 with a byte-order mark.
 *
 * <p>The mark is what makes the file readable on Windows. PowerShell 5.1 decodes a file with no BOM
 * in the host's ANSI codepage, so a UTF-8 {@code —} arrives as {@code â€”} and a {@code ·} as
 * {@code Â·}; with the mark it decodes as UTF-8. Every other reader in the set — PowerShell 7,
 * {@code cmd}'s {@code type}, git, editors, markdown renderers — honours or ignores it. Three bytes
 * buy a report that reads the same on every host, so jk keeps its own typography instead of folding
 * the text down to ASCII.
 *
 * <p>Machine-read files are not written through here: the JSONL transcript and the TOML jk reads
 * back have parsers a mark breaks. {@link #strip} is the other half, for the places jk hands a
 * report's text to a caller that asked for markdown rather than for a file.
 */
public final class MarkdownReports {

    /** U+FEFF, the mark itself — {@code EF BB BF} once UTF-8 encoded. */
    public static final String BOM = "﻿";

    private MarkdownReports() {}

    /** Write {@code markdown} to {@code target} atomically, the mark first. */
    public static void write(Path target, String markdown) throws IOException {
        AtomicWrites.replace(target, BOM + markdown);
    }

    /** {@code text} without a leading mark, for a caller that wants the markdown and not the file. */
    public static String strip(String text) {
        return text.startsWith(BOM) ? text.substring(BOM.length()) : text;
    }
}
