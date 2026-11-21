package org.springframework.samples.petclinic.owner;

/** An owner's audit line, read through the method the audit format names. */
final class OwnerAudit {

	static final String DESCRIBE = "describeForAudit";

	private OwnerAudit() {
	}

	static String line(Owner owner) throws ReflectiveOperationException {
		return (String) Owner.class.getMethod(DESCRIBE).invoke(owner);
	}

}
