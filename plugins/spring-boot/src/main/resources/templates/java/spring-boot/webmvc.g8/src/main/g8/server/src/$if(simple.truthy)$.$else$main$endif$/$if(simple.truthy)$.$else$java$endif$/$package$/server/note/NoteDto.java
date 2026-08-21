package $package$.server.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class NoteDto {

    private NoteDto() {}

    public record View(long id, String body, LocalDateTime createdAt) {}

    public record Create(@NotBlank @Size(max = 2000) String body) {}
}
