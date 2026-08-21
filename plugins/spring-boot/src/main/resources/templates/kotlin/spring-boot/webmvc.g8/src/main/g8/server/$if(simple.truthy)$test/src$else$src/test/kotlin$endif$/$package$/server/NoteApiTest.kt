package $package$.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class NoteApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun apiRequiresAuth() {
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized())
    }

    @Test
    fun listsSeedNote() {
        mvc.perform(get("/api/notes").with(httpBasic("user", "password")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("\u0024[0].body").value("Welcome to JumpKick"))
    }

    @Test
    fun createsNote() {
        mvc.perform(
                post("/api/notes")
                    .with(httpBasic("user", "password"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"body":"from test"}"""),
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("\u0024.body").value("from test"))
            .andExpect(jsonPath("\u0024.id").isNumber())
    }

    @Test
    fun uiIsPublic() {
        mvc.perform(get("/")).andExpect(status().isOk())
    }
}
