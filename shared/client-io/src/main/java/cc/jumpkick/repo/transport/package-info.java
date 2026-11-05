// SPDX-License-Identifier: Apache-2.0

/**
 * The scheme-to-transport factory. It sits above both {@code repo}, which owns the {@code
 * RepoTransport} SPI with its http and file implementations, and {@code repo.s3}, which implements
 * that SPI for object stores, so each of those depends on nothing below it here.
 */
@NullMarked
package cc.jumpkick.repo.transport;

import org.jspecify.annotations.NullMarked;
