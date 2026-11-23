// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.orchestrator.TestProgressListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The runner's {@code tag-excluded} warning, kept for a {@code --class} verdict. */
class TagExcludedCaptureTest {

    private static final String TAG_EXCLUDED = "--class named 1 class the tag filter excluded:"
            + " a.FormatThreadsBenchTest [bench]; drop --exclude-tags bench"
            + " to run it";

    @Test
    void the_capture_keeps_the_runners_tag_excluded_warning_and_forwards_every_warning() {
        List<String> forwarded = new ArrayList<>();
        TestProgressListener sink = new TestProgressListener() {
            @Override
            public void onWarning(String code, String message) {
                forwarded.add(code);
            }
        };
        var capture = new TagExcludedCapture(sink, true);
        capture.onWarning("no-test-classes", "nothing here");
        assertThat(capture.message()).isNull();
        capture.onWarning("tag-excluded", TAG_EXCLUDED);
        assertThat(capture.message()).isEqualTo(TAG_EXCLUDED);
        assertThat(forwarded).containsExactly("no-test-classes", "tag-excluded");

        // A run that only took classes away (exclude-classes, settled classes) named none for the
        // tag filter to drop: the runner's warning about the classes it left out is not passed on.
        forwarded.clear();
        var unnamed = new TagExcludedCapture(sink, false);
        unnamed.onWarning("tag-excluded", TAG_EXCLUDED);
        unnamed.onWarning("no-test-classes", "nothing here");
        assertThat(unnamed.message()).isNull();
        assertThat(forwarded).containsExactly("no-test-classes");
    }
}
