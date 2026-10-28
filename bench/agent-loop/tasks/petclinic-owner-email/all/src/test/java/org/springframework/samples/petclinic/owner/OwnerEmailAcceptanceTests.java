package org.springframework.samples.petclinic.owner;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Hidden acceptance test for the change task: owners carry an optional, validated email. */
@WebMvcTest(OwnerController.class)
@DisabledInNativeImage
@DisabledInAotMode
class OwnerEmailAcceptanceTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OwnerRepository owners;

    private Owner george() {
        Owner george = new Owner();
        george.setId(1);
        george.setFirstName("George");
        george.setLastName("Franklin");
        george.setAddress("110 W. Liberty St.");
        george.setCity("Madison");
        george.setTelephone("6085551023");
        george.setEmail("george@example.com");
        return george;
    }

    @Test
    void theDetailsPageShowsTheEmail() throws Exception {
        given(this.owners.findById(1)).willReturn(Optional.of(george()));
        mockMvc.perform(get("/owners/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("george@example.com")));
    }

    @Test
    void theEditFormCarriesTheEmail() throws Exception {
        given(this.owners.findById(1)).willReturn(Optional.of(george()));
        mockMvc.perform(get("/owners/1/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("george@example.com")));
    }

    @Test
    void aNewOwnerIsSavedWithTheEmail() throws Exception {
        mockMvc.perform(post("/owners/new")
                        .param("firstName", "Joe")
                        .param("lastName", "Bloggs")
                        .param("address", "123 Caramel Street")
                        .param("city", "London")
                        .param("telephone", "1316761638")
                        .param("email", "joe@example.com"))
                .andExpect(status().is3xxRedirection());
        verify(this.owners).save(argThat((Owner o) -> "joe@example.com".equals(o.getEmail())));
    }

    @Test
    void anOwnerWithoutAnEmailIsStillValid() throws Exception {
        mockMvc.perform(post("/owners/new")
                        .param("firstName", "Joe")
                        .param("lastName", "Bloggs")
                        .param("address", "123 Caramel Street")
                        .param("city", "London")
                        .param("telephone", "1316761638"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aMalformedEmailIsRejected() throws Exception {
        mockMvc.perform(post("/owners/new")
                        .param("firstName", "Joe")
                        .param("lastName", "Bloggs")
                        .param("address", "123 Caramel Street")
                        .param("city", "London")
                        .param("telephone", "1316761638")
                        .param("email", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("owner", "email"));
    }

    @Test
    void everyDatabaseSchemaHasTheColumn() throws Exception {
        for (String db : new String[] {"h2", "mysql", "postgres"}) {
            String schema = new ClassPathResource("db/" + db + "/schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .toLowerCase();
            int owners = schema.indexOf("create table if not exists owners");
            if (owners < 0) {
                owners = schema.indexOf("create table owners");
            }
            int end = schema.indexOf(";", owners);
            if (owners < 0 || !schema.substring(owners, end).contains("email")) {
                throw new AssertionError(db + "/schema.sql: the owners table has no email column");
            }
        }
    }
}
