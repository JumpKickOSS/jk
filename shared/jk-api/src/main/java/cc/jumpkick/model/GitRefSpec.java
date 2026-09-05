// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Git ref to resolve: {@link Tag}, {@link Branch}, or {@link Rev}. All pin in {@code jk-lock.toml};
 * only {@code jk update --git}/{@code jk fetch} re-resolves (branch tip moves; tag/rev should not).
 * Shallow clone is governed by {@link GitSource#shallow()}, not this type alone.
 */
public sealed interface GitRefSpec {

    /** Token suitable for embedding in canonical lockfile/source strings. */
    String token();

    /** Whether the spec is intrinsically reproducible (a full SHA). */
    default boolean isPin() {
        return false;
    }

    record Tag(String name) implements GitRefSpec {
        public Tag {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String token() {
            return "tag=" + name;
        }
    }

    record Branch(String name) implements GitRefSpec {
        public Branch {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String token() {
            return "branch=" + name;
        }
    }

    record Rev(String sha) implements GitRefSpec {
        public Rev {
            Objects.requireNonNull(sha, "sha");
        }

        @Override
        public String token() {
            return "rev=" + sha;
        }

        @Override
        public boolean isPin() {
            return true;
        }
    }
}
