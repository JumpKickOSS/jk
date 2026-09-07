// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;

/** What the lock resolved: every artifact with the repository it came from and the scopes it serves. */
public record Lock(List<Artifact> artifacts) {

    public Lock {
        artifacts = List.copyOf(artifacts);
    }

    /**
     * @param coordinate {@code group:artifact}
     * @param version the resolved version
     * @param repository the repository name the lock's {@code source} names ({@code central})
     * @param scopes scopes this artifact serves, by table name
     */
    public record Artifact(String coordinate, String version, String repository, List<String> scopes)
            implements ModelSite {
        public Artifact {
            scopes = List.copyOf(scopes);
        }

        @Override
        public String key() {
            return "lock " + coordinate;
        }
    }
}
