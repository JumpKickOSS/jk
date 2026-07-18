// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Optional object-store settings for {@code s3://}/{@code gs://} repositories. Unset fields fall
 * back to the AWS/default chain.
 */
public record ObjectStoreConfig(
        String region, String endpoint, String accessKey, String secretKey, String sessionToken) {

    public static final ObjectStoreConfig EMPTY = new ObjectStoreConfig(null, null, null, null, null);

    /** True when no field is set (the table declared no object-store config). */
    public boolean isEmpty() {
        return region == null && endpoint == null && accessKey == null && secretKey == null && sessionToken == null;
    }

    /** True when both an access key and secret key are present (explicit credentials). */
    public boolean hasExplicitCredentials() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
