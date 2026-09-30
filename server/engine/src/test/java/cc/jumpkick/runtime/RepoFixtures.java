// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A {@code file://} Maven repository written by a test, so version and POM lookups stay local. */
public final class RepoFixtures {

    private RepoFixtures() {}

    /** A repository entry for each version: {@code maven-metadata.xml} listing them, and a POM each. */
    public static void module(Path repo, String group, String artifact, String... versions) throws IOException {
        Path dir = Files.createDirectories(repo.resolve(group.replace('.', '/')).resolve(artifact));
        StringBuilder list = new StringBuilder();
        for (String v : versions) {
            list.append("      <version>").append(v).append("</version>\n");
            Path versionDir = Files.createDirectories(dir.resolve(v));
            Files.writeString(versionDir.resolve(artifact + "-" + v + ".pom"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </project>
                    """.formatted(group, artifact, v));
        }
        Files.writeString(dir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, list));
    }
}
