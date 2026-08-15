// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreviewImageFetchTest {

    @Test
    void allows_common_readme_image_hosts() {
        assertThat(PreviewImageFetch.isAllowedImageHost("img.shields.io")).isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("github.com")).isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("raw.githubusercontent.com"))
                .isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("user-images.githubusercontent.com"))
                .isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("objects.githubusercontent.com"))
                .isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("camo.githubusercontent.com"))
                .isTrue();
        assertThat(PreviewImageFetch.isAllowedImageHost("evil.example")).isFalse();
        assertThat(PreviewImageFetch.isAllowedImageHost("127.0.0.1")).isFalse();
        assertThat(PreviewImageFetch.isAllowedImageHost(null)).isFalse();
    }

    @Test
    void rejects_missing_or_non_http_urls() {
        assertThat(PreviewImageFetch.fetch(null)).isInstanceOf(PreviewImageFetch.Result.BadRequest.class);
        assertThat(PreviewImageFetch.fetch("")).isInstanceOf(PreviewImageFetch.Result.BadRequest.class);
        assertThat(PreviewImageFetch.fetch("ftp://img.shields.io/x"))
                .isInstanceOf(PreviewImageFetch.Result.BadRequest.class);
        assertThat(PreviewImageFetch.fetch("https://evil.example/a.png"))
                .isInstanceOf(PreviewImageFetch.Result.Forbidden.class);
    }
}
