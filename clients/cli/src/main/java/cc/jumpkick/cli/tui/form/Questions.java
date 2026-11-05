// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Values;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The widget for each field kind. */
final class Questions {

    private Questions() {}

    /** The question for {@code field}, given the values settled before it. */
    static Question<?> of(Field field, Map<String, Object> values, FormSources sources) {
        @Nullable Object fallback = Form.defaultOf(field, sources.derived());
        @Nullable String text = fallback == null ? null : Values.text(fallback);
        return switch (field.kind()) {
            case TEXT -> new LinePrompt(field.label(), field.help(), text, field.pattern(), field.required());
            case BOOL -> new YesNo(field.label(), field.help(), (Boolean) fallback);
            case CHOICE -> new ListSelect(field.label(), field.help(), field.options(), text);
            case MULTI -> new ToggleList(field.label(), field.help(), field.options(), strings(fallback));
            case SEARCH -> {
                String endpoint = field.sourceFor(values);
                yield new Typeahead(
                        field.label(), field.help(), q -> sources.search().matches(field, endpoint, q), text);
            }
            case PATH -> new PathPrompt(field.label(), field.help(), text, field.required(), sources.paths());
        };
    }

    private static List<String> strings(@Nullable Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
