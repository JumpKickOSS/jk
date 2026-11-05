// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link Form#check} made of a set of answers. {@code values} holds every settled field in
 * form order: an answer, or the default of a field that was left out or hidden. {@code hidden}
 * names the fields whose {@code when} was false.
 */
public record FormCheck(Map<String, Object> values, Set<String> hidden, List<Problem> problems) {

    /** One reason the answers are not complete; {@code key} is the field it is about. */
    public record Problem(String key, String message) {}

    public FormCheck {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        hidden = Set.copyOf(hidden);
        problems = List.copyOf(problems);
    }

    public boolean ok() {
        return problems.isEmpty();
    }
}
