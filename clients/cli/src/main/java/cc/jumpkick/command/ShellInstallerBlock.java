// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker-bounded shell rc / profile block (grok-style). Ensures platform bin on PATH, evals hook
 * integration, and wires completions under the data directory.
 */
public final class ShellInstallerBlock {

    public static final String BEGIN = "# >>> jk installer >>>";
    public static final String END = "# <<< jk installer <<<";

    private static final Pattern BLOCK =
            Pattern.compile(Pattern.quote(BEGIN) + "[\\s\\S]*?" + Pattern.quote(END), Pattern.MULTILINE);

    private ShellInstallerBlock() {}

    /** Full block for {@code shell}, with absolute {@code binDir} and {@code dataDir}. */
    public static String render(Shell shell, Path binDir, Path dataDir) {
        String bin = binDir.toAbsolutePath().normalize().toString();
        String data = dataDir.toAbsolutePath().normalize().toString();
        String hooks = shell.activationLine("jk"); // uses `command jk` idioms
        String completions = shell.completionWiring(data);
        StringBuilder sb = new StringBuilder();
        sb.append(BEGIN).append('\n');
        sb.append("# JumpKick: PATH, directory env hooks, completions\n");
        sb.append(shell.pathEnsureSnippet(bin));
        sb.append(hooks).append('\n');
        if (completions != null && !completions.isBlank()) {
            sb.append(completions);
            if (!completions.endsWith("\n")) sb.append('\n');
        }
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
