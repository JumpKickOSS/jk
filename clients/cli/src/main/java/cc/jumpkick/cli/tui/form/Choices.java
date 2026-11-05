// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.form.Option;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Reading a cooked pick against a list of options: a 1-based position, an id, or a label. */
final class Choices {

    private Choices() {}

    /** The option {@code token} names, or {@code null}. Ids and labels match without case. */
    static @Nullable Option pick(List<Option> options, String token) {
        try {
            int n = Integer.parseInt(token);
            return n >= 1 && n <= options.size() ? options.get(n - 1) : null;
        } catch (NumberFormatException notANumber) {
            String lower = token.toLowerCase(Locale.ROOT);
            for (Option o : options) {
                if (o.id().toLowerCase(Locale.ROOT).equals(lower)
                        || o.display().toLowerCase(Locale.ROOT).equals(lower)) {
                    return o;
                }
            }
            return null;
        }
    }

    static String range(List<Option> options) {
        return "Pick 1-" + options.size() + " or a listed id.";
    }

    static int indexOf(List<Option> options, @Nullable String id) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** 1-based positions of {@code ids} in {@code options}, space-separated. */
    static String positions(List<Option> options, List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            int i = indexOf(options, id);
            if (i >= 0) out.add(String.valueOf(i + 1));
        }
        return String.join(" ", out);
    }
}
