// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.DepFrequency;
import cc.jumpkick.cli.tui.Choice;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewWizardLibraryPickerTest {

    @Test
    void java_defaults_then_frequency_fill_capped(@TempDir Path buildsRoot) throws Exception {
        DepFrequency.empty()
                .observe("proj-a", Set.of("guava", "jspecify", "commons-io", "assertj-core"))
                .observe("proj-b", Set.of("guava", "commons-io", "okhttp"))
                .observe("proj-c", Set.of("guava", "tomlj"))
                .save(buildsRoot);

        List<Choice> choices = compose("java", DepFrequency.load(buildsRoot));
        assertThat(choices).hasSizeLessThanOrEqualTo(NewWizard.LIBRARY_PICKER_CAP);
        assertThat(choices.get(0).id()).isEqualTo("jspecify");
        assertThat(choices.get(1).id()).isEqualTo("lombok");
        assertThat(choices).allSatisfy(c -> assertThat(c.hint()).isNullOrEmpty());
        assertThat(choices.stream().map(Choice::id).toList())
                .contains("guava", "commons-io")
                .doesNotHaveDuplicates();
        assertThat(choices.stream().map(Choice::id).filter("jspecify"::equals).count())
                .isEqualTo(1);
    }

    @Test
    void kotlin_fixed_defaults_without_frequency() {
        List<Choice> choices = NewWizard.libraryPickerChoices("kotlin");
        assertThat(choices.stream().map(Choice::id).toList())
                .startsWith(
                        "kotlinx-coroutines-core",
                        "kotlinx-serialization-json",
                        "okhttp",
                        "ktor-server-core",
                        "koin-core");
        assertThat(choices).hasSizeLessThanOrEqualTo(NewWizard.LIBRARY_PICKER_CAP);
        assertThat(choices).allSatisfy(c -> assertThat(c.hint()).isNullOrEmpty());
    }

    @Test
    void java_empty_host_is_just_defaults() {
        List<Choice> choices = compose("java", DepFrequency.empty());
        assertThat(choices.stream().map(Choice::id).toList()).containsExactly("jspecify", "lombok");
    }

    /** Same composition as {@link NewWizard#libraryPickerChoices(String)} with an injected frequency. */
    private static List<Choice> compose(String lang, DepFrequency freq) {
        List<String> fixed =
                "kotlin".equals(lang) ? NewWizard.KOTLIN_LIBRARY_DEFAULTS : NewWizard.JAVA_LIBRARY_DEFAULTS;
        var seen = new LinkedHashSet<>(fixed);
        var out = new ArrayList<Choice>();
        for (String id : fixed) out.add(new Choice(id, id));
        int remaining = NewWizard.LIBRARY_PICKER_CAP - out.size();
        if (remaining > 0) {
            for (String id : freq.top(remaining, seen)) {
                if (id == null || id.isBlank() || !seen.add(id)) continue;
                out.add(new Choice(id, id));
                if (out.size() >= NewWizard.LIBRARY_PICKER_CAP) break;
            }
        }
        return List.copyOf(out);
    }
}
