// SPDX-License-Identifier: Apache-2.0
/**
 * The {@code [schema-registry]} plugin ships its manifest alone: {@code jk lock} pins each subject
 * as a {@code [[schema]]} row and a build hands the pinned schemas to the generator of their type.
 */
package cc.jumpkick.schemaregistry;
