// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.List;
import java.util.stream.Collectors;

/** How an answer reads as text: a {@code multi} joins on commas, a {@code bool} is true or false. */
public final class Values {

    private Values() {}

    public static String text(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }
}
