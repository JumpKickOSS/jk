// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates.testing;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Points a suite's shared sandbox home at {@link LocalTemplateCatalog}: its {@code config.toml}
 * names the local repository as {@code [templates] official}, so every engine the suite starts in
 * that home clones its catalog without reaching github.com. A suite that starts engines lists this
 * class in its {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 *
 * <p>Registered via {@code META-INF/services}, so it runs once per test JVM, before the first
 * test and before any overlay moves {@code JK_HOME}. It touches nothing outside a sandbox slot: a
 * test JVM run against a developer's own home leaves that home's configuration alone. The test
 * JVMs of one suite share the home, so the edit is made under a lock and is idempotent: the block
 * this class owns, between its two marker lines, is replaced; a {@code [templates]} table someone
 * else wrote is left as it is.
 */
public final class SharedHomeTemplateCatalog implements TestExecutionListener {

    static final String BEGIN = "# >>> the official template catalog as this checkout ships it (test sandbox)";
    static final String END = "# <<< the official template catalog";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        Optional<Path> slot = LocalTemplateCatalog.sandboxSlot();
        if (slot.isEmpty()) return;
        String url = LocalTemplateCatalog.url();
        Path config = JkDirs.userConfigFile();
        try {
            Path lockFile = Files.createDirectories(slot.get().resolve("template-catalog"))
                    .resolve("config.lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = channel.lock()) {
                String current = Files.isRegularFile(config) ? Files.readString(config) : "";
                String next = withCatalog(current, url);
                if (!next.equals(current)) {
                    Files.createDirectories(config.getParent());
                    AtomicWrites.replace(config, next);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot point the sandbox home at the local template catalog", e);
        }
    }

    /**
     * {@code config} with this class's block naming {@code url}, appended after the rest; unchanged
     * when the file has a {@code [templates]} table of its own.
     */
    static String withCatalog(String config, String url) {
        String rest = config;
        int begin = rest.indexOf(BEGIN);
        if (begin >= 0) {
            int end = rest.indexOf(END, begin);
            int after = end < 0 ? rest.length() : rest.indexOf('\n', end) + 1;
            if (after <= 0) after = rest.length();
            rest = rest.substring(0, begin) + rest.substring(after);
        }
        if (rest.lines().anyMatch(line -> line.strip().equals("[templates]"))) return config;
        StringBuilder out = new StringBuilder(rest);
        if (!rest.isEmpty() && !rest.endsWith("\n")) out.append('\n');
        if (!rest.isBlank() && !rest.endsWith("\n\n")) out.append('\n');
        out.append(BEGIN)
                .append('\n')
                .append("[templates]\n")
                .append("official = \"")
                .append(url)
                .append("\"\n")
                .append(END)
                .append('\n');
        return out.toString();
    }
}
