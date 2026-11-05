package $package$.note;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

@Entity
public class Note {

    /** Null until the entity is saved: the database assigns it. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @NotBlank
    private String text;

    protected Note() { // JPA: Hibernate sets the fields after it constructs the entity
        this.text = "";
    }

    public Note(String text) {
        this.text = text;
    }

    public @Nullable Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
