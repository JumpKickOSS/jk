// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import cc.jumpkick.guard.api.runtime.GuardExtension;
import cc.jumpkick.guard.api.runtime.GuardRuntime;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.ViolationStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code FreezingArchRule}'s store backed by {@code jk-guards-baseline.toml}: the entries frozen
 * under the running {@code @Guard}'s id are the known violations, so a frozen rule reports only
 * what is new. Read-only — {@link #save} records nothing, because a run never writes the baseline;
 * {@code jk guard freeze <id> --reason} does, tighten-only, through the engine. Configure with
 * {@code freeze.store=cc.jumpkick.guard.api.archunit.JkViolationStore} and
 * {@code freeze.lineMatcher=cc.jumpkick.guard.api.archunit.JkLineMatcher} in {@code archunit.properties},
 * or call {@link JkArchUnit#configureFreezing()}.
 */
public final class JkViolationStore implements ViolationStore {

    private @Nullable Path baseline;

    @Override
    public void initialize(Properties properties) {
        GuardRuntime rt = GuardRuntime.current();
        baseline = rt == null ? null : rt.root().resolve("jk-guards-baseline.toml");
    }

    /** Always: the baseline is the answer, even when it holds nothing for this guard. */
    @Override
    public boolean contains(ArchRule rule) {
        return true;
    }

    /** A run does not write the baseline; {@code jk guard freeze} does. */
    @Override
    public void save(ArchRule rule, List<String> violations) {
        // intentionally nothing
    }

    @Override
    public List<String> getViolations(ArchRule rule) {
        String id = GuardExtension.currentGuardId();
        if (id == null || baseline == null || !Files.isRegularFile(baseline)) return List.of();
        try {
            return entriesFor(Files.readString(baseline), id);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The {@code at} values of {@code [[<id>.entries]]} in a baseline file. */
    static List<String> entriesFor(String toml, String id) {
        List<String> out = new ArrayList<>();
        Pattern entry = Pattern.compile("(?m)^\\[\\[" + Pattern.quote(id) + "\\.entries]]\\s*\\n((?:(?!\\[).*\\n?)*)");
        Pattern at = Pattern.compile("(?m)^at\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = entry.matcher(toml);
        while (m.find()) {
            Matcher a = at.matcher(m.group(1));
            if (a.find()) out.add(a.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out;
    }
}
