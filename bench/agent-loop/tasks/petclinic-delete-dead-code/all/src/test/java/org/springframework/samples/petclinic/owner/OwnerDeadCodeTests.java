package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: the dead code is gone and the reflective use still works. */
class OwnerDeadCodeTests {

	@Test
	void theUnusedMethodsAndTypeAreGone() {
		assertThat(Arrays.stream(Owner.class.getDeclaredMethods()).map(Method::getName))
			.doesNotContain("formatLegacyName", "hasPets");
		assertThatThrownBy(() -> Class.forName("org.springframework.samples.petclinic.owner.OwnerLegacyFormat"))
			.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void theMethodTheAuditCallsByNameIsKept() throws ReflectiveOperationException {
		Owner owner = new Owner();
		owner.setId(7);
		owner.setCity("Madison");
		assertThat(OwnerAudit.line(owner)).isEqualTo("owner 7 in Madison");
	}

}
