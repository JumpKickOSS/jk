package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every owner-not-found message is "Owner <id> not found.", whichever handler throws it. */
class OwnerNotFoundMessageAcceptanceTests {

    private static OwnerRepository empty() {
        OwnerRepository owners = mock(OwnerRepository.class);
        when(owners.findById(999)).thenReturn(Optional.empty());
        return owners;
    }

    @Test
    void everyHandlerSaysTheSame() {
        assertThatThrownBy(() -> new OwnerController(empty()).findOwner(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Owner 999 not found.");
        assertThatThrownBy(() -> new OwnerController(empty()).showOwner(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Owner 999 not found.");
        assertThatThrownBy(() -> new VisitController(empty()).loadPetWithVisit(999, 1, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Owner 999 not found.");
    }
}
