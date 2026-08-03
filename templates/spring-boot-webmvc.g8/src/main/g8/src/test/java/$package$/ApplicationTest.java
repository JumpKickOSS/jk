package $package$;

import static org.assertj.core.api.Assertions.assertThat;

import $package$.note.Note;
import $package$.note.NoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationTest {

    @Autowired
    private NoteRepository notes;

    @Test
    void contextLoadsAndPersists() {
        Note saved = notes.save(new Note("hello"));
        assertThat(saved.getId()).isNotNull();
        assertThat(notes.findById(saved.getId())).hasValueSatisfying(n ->
                assertThat(n.getText()).isEqualTo("hello"));
    }
}
