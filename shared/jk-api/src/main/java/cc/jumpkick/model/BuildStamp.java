// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * The commit a native image of jk's client was linked from. The image build passes it as the
 * system property {@value BuildIdentity#COMMIT_PROPERTY} and initializes this class at build time,
 * so the value is part of the image; on a JVM the property is unset and the field is empty.
 */
final class BuildStamp {

    static final String COMMIT = System.getProperty(BuildIdentity.COMMIT_PROPERTY, "");

    private BuildStamp() {}
}
