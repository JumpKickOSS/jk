package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.system.PhoneReport;

/** Hidden acceptance test for the change task: both statics are declared in PhoneNumbers alone, and every caller still formats. */
class PhoneNumbersMoveTests {

	@Test
	void phoneNumbersDeclaresThemAndOwnerDoesNot() throws ReflectiveOperationException {
		Class<?> target = Class.forName("org.springframework.samples.petclinic.model.PhoneNumbers");
		Method format = target.getDeclaredMethod("formatTelephone", String.class);
		Field digits = target.getDeclaredField("TELEPHONE_DIGITS");
		assertThat(Modifier.isStatic(format.getModifiers())).isTrue();
		assertThat(Modifier.isStatic(digits.getModifiers())).isTrue();
		assertThat(digits.getInt(null)).isEqualTo(10);
		assertThat(format.invoke(null, "6085551023")).isEqualTo("(608) 555-1023");
		assertThat(Arrays.stream(Owner.class.getDeclaredMethods()).map(Method::getName))
			.doesNotContain("formatTelephone");
		assertThat(Arrays.stream(Owner.class.getDeclaredFields()).map(Field::getName))
			.doesNotContain("TELEPHONE_DIGITS");
	}

	@Test
	void everyCallerStillFormats() {
		Owner owner = new Owner();
		owner.setLastName("Franklin");
		owner.setTelephone("6085551023");
		assertThat(OwnerPhoneLabels.label(owner)).isEqualTo("Franklin: (608) 555-1023");
		assertThat(OwnerPhoneLabels.all(List.of("6085551023", "12"))).containsExactly("(608) 555-1023", "12");
		assertThat(PhoneReport.line("6085551023")).isEqualTo("(608) 555-1023");
		assertThat(PhoneReport.line("12")).isEqualTo("invalid");
	}

}
