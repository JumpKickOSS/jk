// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Opt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every visible option has a one-letter short form, unique within its command and the global
 * options, so an agent or a person can always type the short spelling. A command with more options
 * than letters names the ones it leaves long-only here.
 */
class ShortFlagsTest {

    /** {@code command path → long option}: no letter is left once the shared and global options are placed. */
    private static final Set<String> LONG_ONLY = Set.of(
            "install --with",
            "install --ver",
            "install --bin",
            "install --destination",
            "install --snapshot",
            "snapshot --destination",
            "publish --tag",
            "tool install --with",
            "tool install --ver",
            "tool install --bin",
            "tool install --destination",
            "tool install --snapshot",
            "guard --schema");

    @Test
    void every_visible_option_has_a_short_form_unique_in_its_command() {
        List<String> missing = new ArrayList<>();
        List<String> clashes = new ArrayList<>();
        for (CliCommand c : CommandDispatch.commands()) walk(c, c.name(), missing, clashes);
        assertThat(missing).as("visible options with no short form: " + missing).isEmpty();
        assertThat(clashes).as("short forms used twice in one command").isEmpty();
    }

    @Test
    void every_global_option_has_a_short_form() {
        for (Opt o : GlobalOptions.globalOpts()) {
            assertThat(o.names()).as("%s", o.names()).anyMatch(n -> n.length() == 2);
        }
    }

    @Test
    void the_long_only_list_names_real_options() {
        Map<String, Boolean> seen = new HashMap<>();
        for (CliCommand c : CommandDispatch.commands()) collect(c, c.name(), seen);
        for (String entry : LONG_ONLY) assertThat(seen).as(entry).containsKey(entry);
    }

    private static void walk(CliCommand c, String path, List<String> missing, List<String> clashes) {
        Map<String, String> letters = new HashMap<>();
        List<Opt> all = new ArrayList<>(GlobalOptions.globalOpts());
        all.addAll(c.options());
        for (Opt o : all) {
            String shortName =
                    o.names().stream().filter(n -> n.length() == 2).findFirst().orElse(null);
            String longName = o.names().stream()
                    .filter(n -> n.startsWith("--"))
                    .findFirst()
                    .orElse("?");
            if (shortName == null) {
                if (!o.hidden() && !LONG_ONLY.contains(path + " " + longName)) missing.add(path + " " + longName);
                continue;
            }
            String prior = letters.put(shortName, longName);
            if (prior != null && !prior.equals(longName))
                clashes.add(path + " " + shortName + " " + prior + "/" + longName);
        }
        for (CliCommand s : c.subcommands()) walk(s, path + " " + s.name(), missing, clashes);
    }

    private static void collect(CliCommand c, String path, Map<String, Boolean> seen) {
        for (Opt o : c.options()) {
            o.names().stream()
                    .filter(n -> n.startsWith("--"))
                    .findFirst()
                    .ifPresent(l -> seen.put(path + " " + l, true));
        }
        for (CliCommand s : c.subcommands()) collect(s, path + " " + s.name(), seen);
    }
}
