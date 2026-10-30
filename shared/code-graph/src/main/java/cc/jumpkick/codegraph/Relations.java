// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

/** The relation names the extractors write; an edge may carry any other string as well. */
public final class Relations {

    private Relations() {}

    public static final String CONTAINS = "contains";
    public static final String METHOD = "method";
    public static final String INHERITS = "inherits";
    public static final String IMPLEMENTS = "implements";
    public static final String IMPORTS = "imports";
    public static final String IMPORTS_FROM = "imports_from";
    public static final String RE_EXPORTS = "re_exports";
    public static final String CALLS = "calls";
    /** A method to the supertype method it overrides or implements: a call to that one may run this one. */
    public static final String OVERRIDES = "overrides";

    public static final String REFERENCES = "references";
    public static final String USES = "uses";
    public static final String RATIONALE_FOR = "rationale_for";
    public static final String CONFIGURES = "configures";
    public static final String DEPENDS_ON = "depends_on";
}
