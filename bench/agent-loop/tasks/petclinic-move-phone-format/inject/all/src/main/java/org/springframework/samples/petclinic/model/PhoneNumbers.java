package org.springframework.samples.petclinic.model;

/** Telephone numbers as the clinic stores and shows them. */
public final class PhoneNumbers {

	private PhoneNumbers() {
	}

	/** The digits of {@code text}, every other character dropped. */
	public static String digits(String text) {
		return text.replaceAll("\\D", "");
	}

}
