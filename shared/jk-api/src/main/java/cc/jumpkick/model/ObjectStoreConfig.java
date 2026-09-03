// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import org.jspecify.annotations.Nullable;

/**
 * Optional object-store settings for {@code s3://}/{@code gs://} repositories. Unset fields fall
 * back to the AWS/default chain.
 */
public record ObjectStoreConfig(
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String accessKey,
        @Nullable String secretKey,
        @Nullable String sessionToken) {

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
