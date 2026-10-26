package org.apache.commons.lang3.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: every ToStringStyle says its name. */
class ToStringStyleNameAcceptanceTest {

    @Test
    void theBuiltInStylesAreNamedByTheirConstant() {
        assertEquals("DEFAULT_STYLE", ToStringStyle.DEFAULT_STYLE.getName());
        assertEquals("MULTI_LINE_STYLE", ToStringStyle.MULTI_LINE_STYLE.getName());
        assertEquals("NO_FIELD_NAMES_STYLE", ToStringStyle.NO_FIELD_NAMES_STYLE.getName());
        assertEquals("SHORT_PREFIX_STYLE", ToStringStyle.SHORT_PREFIX_STYLE.getName());
        assertEquals("SIMPLE_STYLE", ToStringStyle.SIMPLE_STYLE.getName());
        assertEquals("NO_CLASS_NAME_STYLE", ToStringStyle.NO_CLASS_NAME_STYLE.getName());
        assertEquals("JSON_STYLE", ToStringStyle.JSON_STYLE.getName());
    }

    @Test
    void everyOtherStyleIsNamedByItsClass() {
        assertEquals("StandardToStringStyle", new StandardToStringStyle().getName());
        assertEquals("RecursiveToStringStyle", new RecursiveToStringStyle().getName());
        assertEquals("MultilineRecursiveToStringStyle", new MultilineRecursiveToStringStyle().getName());
    }
}
