// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.Choice;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.cli.tui.WizardStep;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.terminal.Styled;
import cc.jumpkick.terminal.StyledBuilder;
import cc.jumpkick.terminal.TerminalSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TUI for {@code jk jdk uninstall} when no argument is supplied.
 *
 * <p>One vertical radio step over every installed JDK. Each row renders as:
 *
 * <pre>
 *   {source}/{identifier} - {Vendor} {Product}
 * </pre>
 *
 * with {@code source} in bold-yellow, {@code /identifier} in bold-white, and the trailing vendor
 * metadata in dark gray. The currently-installed default JDK gets a {@code (default)} hint so the
 * user sees what they're about to remove.
 *
 * <p>Returns the {@link JdkHit} the user selected. The caller does the confirmation prompt +
 * deletion + default reconciliation — keeping the wizard's responsibility narrow.
 */
final class JdkUninstallWizard {

    static final String VICTIMS_KEY = "victims";

    private JdkUninstallWizard() {}

    /** Show the wizard. Empty result means the user cancelled (Esc). */
    static Optional<JdkHit> run(List<JdkHit> installed, Optional<String> currentDefault, TerminalSession terminal) {
        Map<String, JdkHit> byId = new LinkedHashMap<>();
        for (JdkHit hit : installed) {
            byId.put(choiceIdFor(hit), hit);
        }

        List<Choice> choices = new ArrayList<>();
        for (JdkHit hit : installed) {
            String id = choiceIdFor(hit);
            String identifier = JdkRegistry.identifierFor(hit.home());
            String fallback = richLabel(hit, identifier, false).toString();
            String hint = currentDefault.isPresent() && currentDefault.get().equals(identifier) ? "(default)" : "";
            choices.add(Choice.rich(id, fallback, hint, focused -> richLabel(hit, identifier, focused)));
        }

        Wizard wizard = Wizard.builder()
                .command("Uninstall JDK")
                .subtitle("Remove an installed Java Development Kit")
                .step(WizardStep.RadioStep.vertical(VICTIMS_KEY, "Select a JDK to uninstall")
                        .choicesFn(_ -> choices)
                        .build())
                .build();

        Optional<Answers> outcome = wizard.run(terminal);
        if (outcome.isEmpty()) return Optional.empty();
        return Optional.ofNullable(byId.get(outcome.get().get(VICTIMS_KEY)));
    }

    /**
     * Render-only: {@code source/identifier - Vendor Product} as a mixed-style {@link
     * Styled}.
     *
     * <p>When {@code focused} is {@code true}: source bold-yellow, identifier bold-white. When {@code
     * false}: same colors with the bold dropped, so only the row the user's cursor is sitting on
     * stands out. The trailing vendor metadata is always dark-gray; vendor block omitted when
     * unknown.
     */
    static Styled richLabel(JdkHit hit, String identifier, boolean focused) {
        var sourceStyle =
                focused ? Theme.active().warning().bold() : Theme.active().warning();
        var idStyle = focused ? Theme.active().focused() : Theme.active().plainWhite();
        var sb = new StyledBuilder();
        sb.append(hit.source(), sourceStyle);
        sb.append("/", idStyle);
        sb.append(identifier, idStyle);
        if (hit.vendor() != null && hit.vendor() != JdkVendor.UNKNOWN) {
            sb.append(" - ", Theme.active().darkGray());
            sb.append(hit.vendor().displayName(), Theme.active().darkGray());
        }
        return sb.build();
    }

    /** Unique key for one row: {@code <source>/<identifier>}. */
    static String choiceIdFor(JdkHit hit) {
        return hit.source() + "/" + JdkRegistry.identifierFor(hit.home());
    }
}
