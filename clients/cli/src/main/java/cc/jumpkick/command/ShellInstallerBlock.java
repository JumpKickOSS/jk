// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker-bounded shell rc / profile block. One eval/source line loads PATH, directory hooks, and
 * completions from {@code jk activate &lt;shell&gt;}.
 */
public final class ShellInstallerBlock {

    public static final String BEGIN = "# >>> jk installer >>>";
    public static final String END = "# <<< jk installer <<<";
    public static final String COMMENT = "# JumpKick shell integration. Hi-ya!";

    private static final Pattern BLOCK =
            Pattern.compile(Pattern.quote(BEGIN) + "[\\s\\S]*?" + Pattern.quote(END), Pattern.MULTILINE);

    private ShellInstallerBlock() {}

    /**
     * Full block for {@code shell}. {@code binDir} is the platform bin directory; paths under
     * {@code home} are emitted as {@code $HOME/…} so the snippet is username-free.
     */
    public static String render(Shell shell, Path binDir, Path home) {
        Path jkExe = binDir.toAbsolutePath().normalize().resolve("jk");
        String command = shell.commandExpr(jkExe, home);
        StringBuilder sb = new StringBuilder();
        sb.append(BEGIN).append('\n');
        sb.append(COMMENT).append('\n');
        sb.append(shell.activationLine(command)).append('\n');
        sb.append(END);
        return sb.toString();
    }

    /** Whether {@code rcContent} already contains a jk installer block. */
    public static boolean present(String rcContent) {
        return rcContent != null && rcContent.contains(BEGIN) && rcContent.contains(END);
    }

    /**
     * Insert or replace the installer block in {@code rcContent}. Does not attempt to remove
     * unrelated historical lines.
     */
    public static String upsert(String rcContent, String block) {
        String existing = rcContent == null ? "" : rcContent;
        Matcher m = BLOCK.matcher(existing);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(block));
        }
        String trimmed = existing.stripTrailing();
        if (trimmed.isEmpty()) return block + "\n";
        return trimmed + "\n\n" + block + "\n";
    }
}
