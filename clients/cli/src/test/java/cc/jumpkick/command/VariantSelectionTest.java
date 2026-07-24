// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Freeze: {@code --variant} is repeatable and folds into the compact wire selector used on engine
 * requests. Regression for silent last-wins if {@code .repeat()} is dropped.
 */
class VariantSelectionTest {

    private static Command commandWithVariantOpts() {
        return new Command() {
            @Override
            public String name() {
                return "build";
            }

            @Override
            public String description() {
                return "demo";
            }

            @Override
            public List<Opt> options() {
                return VariantSelection.options();
            }

            @Override
            public List<Param> parameters() {
                return List.of();
            }
        };
    }

    @Test
    void variant_flags_are_declared_repeatable() {
        Opt variant = VariantSelection.options().stream()
                .filter(o -> o.names().contains("--variant"))
                .findFirst()
                .orElseThrow();
        assertThat(variant.repeatable()).isTrue();
    }

    @Test
    void multiple_variant_flags_accumulate_in_selector() throws Exception {
        Invocation in = ArgParser.parse(
                commandWithVariantOpts(), List.of("--variant", "tier=free", "--variant", "contentType=demo"));
        assertThat(in.values("variant")).containsExactly("tier=free", "contentType=demo");
        assertThat(VariantSelection.selector(in)).isEqualTo("tier=free|contentType=demo");
    }

    @Test
    void release_flag_sets_build_type() throws Exception {
        Invocation in = ArgParser.parse(commandWithVariantOpts(), List.of("--release", "--variant", "tier=paid"));
        assertThat(VariantSelection.selector(in)).isEqualTo("release|tier=paid");
    }

    @Test
    void tool_with_flag_is_declared_repeatable() {
        // Tool run/install mount the same --with grammar; drop .repeat() and multi-deps silently fail.
        boolean found = false;
        for (CliCommand cmd : cc.jumpkick.cli.CommandDispatch.commands()) {
            found |= declaresRepeatableWith(cmd);
        }
        assertThat(found)
                .as("at least one registered command declares repeatable --with")
                .isTrue();
    }

    private static boolean declaresRepeatableWith(CliCommand cmd) {
        for (Opt o : cmd.options()) {
            if (o.names().contains("--with") && o.repeatable()) return true;
        }
        for (CliCommand sub : cmd.subcommands()) {
            if (declaresRepeatableWith(sub)) return true;
        }
        return false;
    }
}
