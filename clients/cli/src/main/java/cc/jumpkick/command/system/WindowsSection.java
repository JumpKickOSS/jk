// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.winhost.HostFacts;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsHostCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The {@code windows} rows of {@code jk doctor}: none off Windows. */
final class WindowsSection {

    private WindowsSection() {}

    /** This host's rows; empty off Windows. */
    static List<HostRow> collect() {
        if (!Os.isWindows()) return List.of();
        return check().rows();
    }

    /** The check over this machine, for the current project. */
    static WindowsHostCheck check() {
        return new WindowsHostCheck(
                HostFacts.live(), SetupMemo.read(SetupMemo.file(JkDirs.state())), JkDirs.home(), projectRoot());
    }

    /** The project the working directory is in: the directory its lock belongs to, when it has a {@code jk.toml}. */
    static @Nullable Path projectRoot() {
        try {
            Path dir = LockPaths.lockFile(WorkingDirectory.current()).getParent();
            return dir != null && Files.isRegularFile(dir.resolve(ManifestNames.MANIFEST)) ? dir : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One line per row, its fix after the detail. */
    static List<String> render(List<HostRow> rows, Theme t) {
        List<String> out = new ArrayList<>();
        for (HostRow row : rows) {
            String prefix =
                    switch (row.status()) {
                        case OK -> Theme.colorize("ok:      ", t.completedStep());
                        case WARN -> Theme.colorize("warn:    ", t.warning());
                        case FAIL -> Theme.colorize("fail:    ", t.error());
                    };
            String fix = row.fix() == null || row.status() == HostRow.Status.OK ? "" : " — " + row.fix();
            out.add(prefix + Theme.colorize(row.label(), t.cyan()) + " — " + row.detail() + fix);
        }
        return out;
    }

    /** The {@code windows} member of the JSON report: one object per row. */
    static String json(List<HostRow> rows) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            HostRow row = rows.get(i);
            if (i > 0) array.append(',');
            array.append(JsonFields.object()
                    .string("id", row.id())
                    .string("label", row.label())
                    .string("status", row.status().wire())
                    .string("detail", row.detail())
                    .string("fix", row.fix())
                    .finish());
        }
        return array.append(']').toString();
    }
}
