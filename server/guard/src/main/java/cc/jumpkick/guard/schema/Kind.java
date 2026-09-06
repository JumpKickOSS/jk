// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The closed set of rule kinds. A rule names exactly one; the kind fixes the schema, the substrate,
 * the lane and the read set the cache key is built from. There is no key-presence dispatch and no
 * expression language: "kind selected by key presence" hides the read set the caching depends on.
 */
public enum Kind {
    FORBID("forbid", Substrate.BYTECODE, Lane.MODULE, "ban a type, member, package or call shape outside an owner"),
    ANNOTATE("annotate", Substrate.BYTECODE, Lane.MODULE, "an annotation must or must not be present"),
    CLASSES("classes", Substrate.BYTECODE, Lane.MODULE, "classes that … should … with closed predicates"),
    LAYERS("layers", Substrate.MODEL, Lane.MODEL, "package or module layering and visibility"),
    CYCLES("cycles", Substrate.BYTECODE, Lane.MODULE, "slices free of cycles"),
    SPLIT_PACKAGE("split-package", Substrate.BYTECODE, Lane.WORKSPACE, "one module owns a package"),
    API("api", Substrate.BYTECODE, Lane.WORKSPACE, "public API compatibility ratchet"),
    DEPEND("depend", Substrate.MODEL, Lane.MODEL, "dependency policy over manifest and resolved lock"),
    TOOLCHAIN("toolchain", Substrate.MODEL, Lane.MODEL, "build environment requirements"),
    TIERS("tiers", Substrate.BYTECODE, Lane.MODULE, "test-tier routing"),
    TEXT("text", Substrate.TEXT, Lane.TREE, "a pattern over text"),
    METRIC("metric", Substrate.TEXT, Lane.TREE, "numeric caps and ratchets"),
    VOCABULARY("vocabulary", Substrate.HYBRID, Lane.MODULE, "owner constants banned as literals elsewhere"),
    PARITY("parity", Substrate.TEXT, Lane.TREE, "two extractions must agree"),
    GENERATED("generated", Substrate.TEXT, Lane.TREE, "a block in a file is rendered from a source of truth"),
    OUTPUT("output", Substrate.OUTPUT, Lane.OUTPUT, "build artefact assertions"),
    COMMIT("commit", Substrate.TEXT, Lane.HOOK, "commit-message rules");

    private final String id;
    private final Substrate substrate;
    private final Lane lane;
    private final String summary;

    Kind(String id, Substrate substrate, Lane lane, String summary) {
        this.id = id;
        this.substrate = substrate;
        this.lane = lane;
        this.summary = summary;
    }

    /** The {@code kind = "…"} spelling. */
    public String id() {
        return id;
    }

    public Substrate substrate() {
        return substrate;
    }

    /** The default lane; some kinds move by key ({@code layers edges}, {@code cycles over}). */
    public Lane lane() {
        return lane;
    }

    public String summary() {
        return summary;
    }

    /** Kind-specific keys, in the order {@code explain --schema} prints them. */
    public List<KeySpec> keys() {
        return KindSchemas.keysOf(this);
    }

    /** Groups of keys of which at least one (or, when {@code exactlyOne}, exactly one) must be present. */
    public List<KeyGroup> groups() {
        return KindSchemas.groupsOf(this);
    }

    /** Whether {@code instead} is required, optional (derived when absent) or never read. */
    public InsteadRule instead() {
        return KindSchemas.insteadOf(this);
    }

    /** One complete rule table, ≤60 tokens, that loads cleanly. */
    public String example() {
        return KindSchemas.exampleOf(this);
    }

    public static Optional<Kind> byId(String id) {
        String want = id.toLowerCase(Locale.ROOT);
        for (Kind k : values()) if (k.id.equals(want)) return Optional.of(k);
        return Optional.empty();
    }

    /** A mutually constraining set of keys. */
    public record KeyGroup(List<String> keys, boolean exactlyOne) {
        public KeyGroup {
            keys = List.copyOf(keys);
        }
    }

    public enum InsteadRule {
        REQUIRED,
        OPTIONAL,
        ABSENT
    }
}
