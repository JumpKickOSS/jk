package $package$

import $package$.note.Note
import $package$.note.NoteRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApplicationTest {

    @Autowired
    lateinit var notes: NoteRepository

    @Test
    fun contextLoadsAndPersists() {
        val saved = notes.save(Note("hello"))
        assertThat(saved.id).isNotNull()
        assertThat(notes.findById(saved.id!!).get().text).isEqualTo("hello")
    }
}
