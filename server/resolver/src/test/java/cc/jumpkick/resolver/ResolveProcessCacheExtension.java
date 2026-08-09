// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.GradleModuleMetadata;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Drop process-wide resolve memos between tests. Fixture HTTP/file repos routinely reuse the same
 * GAV with different body content; GAV-keyed production caches would otherwise poison neighbors.
 */
public final class ResolveProcessCacheExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        cc.jumpkick.repo.RepoGroup.clearProcessFetchCache();
        KmpRedirects.clearProcessCache();
    }
}
