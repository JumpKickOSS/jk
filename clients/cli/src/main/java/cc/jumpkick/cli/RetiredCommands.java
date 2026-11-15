// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import java.util.List;
import java.util.Optional;

/**
 * The verbs JumpKick no longer has: the one list dispatch refuses from, with the command to type
 * instead. A verb that used to work is refused by an entry here, never by an alias or a message
 * typed elsewhere.
 */
public final class RetiredCommands {

    private RetiredCommands() {}

    /**
     * One retired verb.
     *
     * @param name the verb as it was typed
     * @param since the first release that refuses it
     * @param instead the command that does its work now
     */
    public record Retired(String name, String since, String instead) {

        /** The one-line refusal. */
        public String refusal() {
            return "jk " + name + " was retired in JumpKick " + since + "; run " + instead;
        }
    }

    /** Every retired verb, oldest release first. */
    public static final List<Retired> ALL = List.of(new Retired("snapshot", "0.51.1", "jk install"));

    /**
     * The retired verb {@code token} names: the verb itself, or a prefix of it when no live command
     * claims {@code token} ({@code live} is false), since the prefix used to reach it.
     */
    public static Optional<Retired> named(String token, boolean live) {
        for (Retired r : ALL) {
            if (r.name().equals(token)) return Optional.of(r);
            if (!live && !token.isEmpty() && r.name().startsWith(token)) return Optional.of(r);
        }
        return Optional.empty();
    }
}
