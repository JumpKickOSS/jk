package $package$.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void apiRequiresAuth() throws Exception {
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsSeedNote() throws Exception {
        mvc.perform(get("/api/notes").with(httpBasic("user", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("\u0024[0].body").value("Welcome to JumpKick"));
    }

    @Test
    void createsNote() throws Exception {
        mvc.perform(post("/api/notes")
                        .with(httpBasic("user", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"from test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("\u0024.body").value("from test"))
                .andExpect(jsonPath("\u0024.id").isNumber());
    }

    @Test
    void uiIsPublic() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
    }
}
