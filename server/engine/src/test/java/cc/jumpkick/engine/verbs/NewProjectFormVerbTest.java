// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import cc.jumpkick.wire.protocol.NewProjectFormRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What the CLI gets for {@code jk new} on a terminal: the form, the resolved id and the catalogue. */
class NewProjectFormVerbTest {

    private final Path repo = RepoRoot.find(NewProjectFormVerbTest.class);

    @Test
    void with_no_template_the_answer_is_jks_step_and_the_catalogue() throws Exception {
        NewProjectFormAck ack = NewProjectFormAck.decode(
                NewProjectFormVerb.answer(new NewProjectFormRequest(null, null, repo.toString()))
                        .encode());
        Form form = Form.fromJson(ack.form());
        assertThat(form.fields()).extracting(Field::key).containsExactly("lang", "template", "name", "group", "dir");
        assertThat(ack.template()).isEmpty();
        assertThat(ack.templates()).containsKeys("java/none/app", "kotlin/none/library");
    }

    @Test
    void a_template_ref_resolves_to_its_id_and_its_questions() throws Exception {
        NewProjectFormAck sent = NewProjectFormVerb.answer(new NewProjectFormRequest("app", "kotlin", repo.toString()));
        NewProjectFormAck ack = NewProjectFormAck.decode(sent.encode());
        assertThat(ack.error()).isNull();
        assertThat(ack.template()).isEqualTo("kotlin/none/app");
        Form form = Form.fromJson(ack.form());
        assertThat(form.field("native")).isNotNull();
        assertThat(form.field("layout")).isNotNull();
    }
}
