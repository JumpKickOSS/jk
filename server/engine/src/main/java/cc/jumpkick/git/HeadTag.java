// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Whether a checkout's HEAD carries a tag, read from its refs in process: no git command runs, so
 * the question costs a build next to nothing when the answer is no.
 */
public final class HeadTag {

    private HeadTag() {}

    /** True when {@code tag} (annotated or lightweight) names the commit HEAD of {@code checkout} is at. */
    public static boolean carries(Path checkout, String tag) throws IOException {
        JGitEnvironment.install();
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(checkout.toFile());
        if (builder.getGitDir() == null) return false;
        try (Repository repo = builder.setMustExist(true).build()) {
            Ref ref = repo.exactRef(Constants.R_TAGS + tag);
            if (ref == null) return false;
            ObjectId head = repo.resolve(Constants.HEAD);
            ObjectId tagged = repo.resolve(Constants.R_TAGS + tag + "^{commit}");
            return head != null && head.equals(tagged);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
