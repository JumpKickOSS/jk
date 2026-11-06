// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TemplateReferencesTest {

    private static final Set<String> UNSET = Set.of("db_name", "token");

    @Test
    void a_bare_use_or_a_formatted_one_is_unguarded() {
        assertThat(TemplateReferences.scan("url = \"$db_name$\"\n", UNSET)).isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$db_name;format=\"Camel\"$", UNSET)).isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$name$ $token$", UNSET)).isEqualTo("token");
    }

    @Test
    void the_branch_of_an_if_on_the_key_guards_it() {
        assertThat(TemplateReferences.scan("$if(db_name)$url = \"$db_name$\"$endif$", UNSET))
                .isNull();
        assertThat(TemplateReferences.scan("$if(token && db_name)$$token$ $db_name.truthy$$endif$", UNSET))
                .isNull();
        assertThat(TemplateReferences.scan("$if(!db_name)$none$else$$db_name$$endif$", UNSET))
                .isNull();
        assertThat(TemplateReferences.scan("$if(db_name)$$if(x)$$db_name$$endif$$endif$", UNSET))
                .isNull();
    }

    @Test
    void outside_the_branch_the_use_is_unguarded() {
        assertThat(TemplateReferences.scan("$if(db_name)$ok$else$$db_name$$endif$", UNSET))
                .isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$if(db_name)$ok$endif$ $db_name$", UNSET))
                .isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$if(db_name || token)$$db_name$$endif$", UNSET))
                .isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$if(db_name)$a$elseif(x)$$db_name$$endif$", UNSET))
                .isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$if(token)$$db_name$$endif$", UNSET))
                .isEqualTo("db_name");
    }

    /** A bare name in a condition only tests the property; {@code key.truthy} reads it. */
    @Test
    void a_condition_reading_a_property_of_the_key_is_a_use() {
        assertThat(TemplateReferences.scan("$if(db_name)$x$endif$", UNSET)).isNull();
        assertThat(TemplateReferences.scan("$if(db_name.truthy)$x$endif$", UNSET))
                .isEqualTo("db_name");
        assertThat(TemplateReferences.scan("$if(db_name)$$if(db_name.truthy)$x$endif$$endif$", UNSET))
                .isNull();
    }

    @Test
    void escapes_comments_strings_and_other_names_are_not_uses() {
        assertThat(TemplateReferences.scan("cost: \\$db_name \\$token", UNSET)).isNull();
        assertThat(TemplateReferences.scan("$! $db_name$ is set below !$", UNSET))
                .isNull();
        assertThat(TemplateReferences.scan("$name;format=\"db_name\"$", UNSET)).isNull();
        assertThat(TemplateReferences.scan("$db_name_extra$ $x.db_name$", UNSET))
                .isNull();
    }
}
