// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.Option;
import java.nio.file.Path;
import java.util.List;

/**
 * What a form needs from outside itself while it is answered: derived defaults, the matches for a
 * search field, and directory completion for a path field.
 */
public record FormSources(DerivedDefaults derived, Search search, PathPrompt.Completer paths) {

    /** The matches for {@code query} from a search field's expanded {@code endpoint}, best first. */
    @FunctionalInterface
    public interface Search {
        List<Option> matches(Field field, String endpoint, String query);
    }

    /** No derived defaults, no search results, and directory completion under {@code base}. */
    public static FormSources local(Path base) {
        return new FormSources(
                DerivedDefaults.NONE, (field, endpoint, query) -> List.of(), PathPrompt.directories(base));
    }
}
