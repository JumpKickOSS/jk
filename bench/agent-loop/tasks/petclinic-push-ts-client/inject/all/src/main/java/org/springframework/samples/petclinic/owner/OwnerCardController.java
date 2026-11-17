/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.owner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner cards the front desk's web client shows (client/src/owner-card.ts), described in
 * docs/openapi.yaml.
 */
@RestController
@RequestMapping("/api/owners")
class OwnerCardController {

    /** One owner as the card shows them. */
    record OwnerCard(int id, String firstName, String lastName, String telephone, int pets) {}

    private final OwnerRepository owners;

    OwnerCardController(OwnerRepository owners) {
        this.owners = owners;
    }

    @GetMapping("/{ownerId}/card")
    OwnerCard card(@PathVariable("ownerId") int ownerId) {
        Owner owner = this.owners.findById(ownerId).orElseThrow();
        return new OwnerCard(
                owner.getId(),
                owner.getFirstName(),
                owner.getLastName(),
                owner.getTelephone(),
                owner.getPets().size());
    }
}
