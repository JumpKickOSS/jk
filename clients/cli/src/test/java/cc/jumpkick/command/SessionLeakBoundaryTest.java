// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code VariantSelectionTest} and {@code BuildCommandTest} drive production install sites and
 * carry no cleanup of their own: {@link VariantSelection#install} writes the variant onto the
 * process static and never puts it back. That is correct for a real invocation — one process, one
 * command — and a leak with no owner in a shared test JVM.
 *
 * <p>This drives the same call and asserts that what contains it is the boundary, not the caller.
 * Ordered, because the claim is about the <em>next</em> test: the leak and the proof it did not
 * survive cannot live in one method.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionLeakBoundaryTest {

    private static final String SELECTOR = "tier=leak-probe";

    @Test
    @Order(1)
    void variant_selection_installs_on_the_static_and_never_restores(@TempDir Path dir) throws Exception {
        assertThat(SessionContext.installed().variant())
                .as("a previous class must not have left a variant here either")
                .isEmpty();

        Invocation in = ArgParser.parse(variantCommand(), List.of("--variant", SELECTOR));
        VariantSelection.install(in, dir);

        assertThat(SessionContext.installed().variant())
                .as("the production path installs and does not put it back — this is the leak")
                .isEqualTo(SELECTOR);
    }

    @Test
    @Order(2)
    void the_next_test_does_not_inherit_that_variant() {
        assertThat(SessionContext.installed().variant())
                .as("SessionBoundary restored the static; without it this is what leaks downstream")
                .isEmpty();
    }

    private static Command variantCommand() {
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
}
