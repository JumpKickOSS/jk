package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.system.PetTypeReport;

/** Hidden acceptance test for the change task: PetType declares typeLabel(Pet) alone, and every caller still labels. */
class PetTypeLabelMoveTests {

	@Test
	void petTypeDeclaresItAndPetDoesNot() throws ReflectiveOperationException {
		Method label = PetType.class.getDeclaredMethod("typeLabel", Pet.class);
		assertThat(Modifier.isStatic(label.getModifiers())).isFalse();
		assertThat(Modifier.isPublic(label.getModifiers())).isTrue();
		assertThat(Arrays.stream(Pet.class.getDeclaredMethods()).map(Method::getName)).doesNotContain("typeLabel");
		assertThat(label.invoke(cat().getType(), cat())).isEqualTo("Leo the cat");
	}

	@Test
	void everyCallerStillLabels() {
		Pet pet = cat();
		Owner owner = new Owner();
		owner.addPet(pet);
		assertThat(PetLabels.label(pet)).isEqualTo("Leo the cat");
		assertThat(PetLabels.all(owner)).containsExactly("Leo the cat");
		assertThat(PetTypeReport.line(pet)).isEqualTo("- Leo the cat");
		assertThat(PetTypeReport.line(new Pet())).isEqualTo("unknown");
	}

	private static Pet cat() {
		PetType type = new PetType();
		type.setName("cat");
		Pet pet = new Pet();
		pet.setName("Leo");
		pet.setType(type);
		return pet;
	}

}
