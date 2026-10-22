// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.resolver.pubgrub.PackageSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Quiesces every package source a test made before JUnit deletes its {@code @TempDir}: a source's
 * prefetch keeps writing into the store under the temp dir after the test's last assertion, and a
 * delete racing that write fails the test. Register with {@code @RegisterExtension} and wrap each
 * source in {@link #track}.
 */
final class QuiescedSources implements AfterEachCallback {

    private final List<PackageSource> sources = new ArrayList<>();

    <S extends PackageSource> S track(S source) {
        synchronized (sources) {
            sources.add(source);
        }
        return source;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<PackageSource> made;
        synchronized (sources) {
            made = List.copyOf(sources);
            sources.clear();
        }
        for (PackageSource s : made) s.quiesce();
    }
}
