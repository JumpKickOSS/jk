package org.springframework.samples.petclinic.system;

import static org.springframework.samples.petclinic.owner.Owner.TELEPHONE_DIGITS;
import static org.springframework.samples.petclinic.owner.Owner.formatTelephone;

/** One telephone as a report line, formatted by {@link org.springframework.samples.petclinic.owner.Owner#formatTelephone}. */
public final class PhoneReport {

	private PhoneReport() {
	}

	public static String line(String telephone) {
		return telephone.length() == TELEPHONE_DIGITS ? formatTelephone(telephone) : "invalid";
	}

}
