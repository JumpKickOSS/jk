// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.util.listener.ChainedRepositoryListener;

/**
 * Every file Quarkus's resolver read while it resolved an application model: each POM (a
 * dependency's, a parent, an imported BOM) and each jar, whether a repository served it or a local
 * tail already held it, as {@code group:artifact:version[:classifier][!type]}. What {@code jk lock}
 * pins for the {@code quarkus/deployment} closure, so the augment's resolver finds every one of them
 * in the store. Lives in the JVM that resolves alone: the worker never loads Aether.
 */
final class ResolvedArtifacts extends AbstractRepositoryListener {

    private final Set<String> files = new ConcurrentSkipListSet<>();
    private final Set<String> skipped;

    /** {@code skipped}: {@code group:artifact:version} of what is not fetched from a repository (the app, siblings). */
    ResolvedArtifacts(Set<String> skipped) {
        this.skipped = Set.copyOf(skipped);
    }

    /** Record what {@code session} resolves from now on, keeping the listener it had. */
    void attach(RepositorySystemSession session) {
        if (!(session instanceof DefaultRepositorySystemSession mutable)) {
            throw new IllegalStateException("the resolver session is read-only; its artifacts cannot be recorded");
        }
        RepositoryListener previous = mutable.getRepositoryListener();
        mutable.setRepositoryListener(previous == null ? this : ChainedRepositoryListener.newInstance(previous, this));
    }

    @Override
    public void artifactResolved(RepositoryEvent event) {
        Artifact a = event.getArtifact();
        if (a == null || event.getFile() == null || !event.getExceptions().isEmpty()) return;
        String gav = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getBaseVersion();
        if (skipped.contains(gav)) return;
        files.add(coordinate(a));
    }

    /** The files recorded so far, sorted. */
    List<String> files() {
        return List.copyOf(files);
    }

    /** {@code a} as jk writes a coordinate: no type for a jar, {@code !<extension>} for any other file. */
    static String coordinate(Artifact a) {
        StringBuilder b = new StringBuilder(a.getGroupId())
                .append(':')
                .append(a.getArtifactId())
                .append(':')
                .append(a.getBaseVersion());
        if (!a.getClassifier().isEmpty()) b.append(':').append(a.getClassifier());
        if (!"jar".equals(a.getExtension())) b.append('!').append(a.getExtension());
        return b.toString();
    }
}
