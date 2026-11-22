package org.apache.commons.lang3.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: clip and its marker are declared in TextClips alone, and every caller still clips. */
class TextClipsMoveTest {

    @Test
    void textClipsDeclaresThemAndStringUtilsDoesNot() throws ReflectiveOperationException {
        final Method clip = TextClips.class.getDeclaredMethod("clip", String.class, int.class);
        final Field marker = TextClips.class.getDeclaredField("CLIP_MARKER");
        assertTrue(Modifier.isStatic(clip.getModifiers()) && Modifier.isPublic(clip.getModifiers()));
        assertTrue(Modifier.isStatic(marker.getModifiers()) && Modifier.isPublic(marker.getModifiers()));
        assertEquals("...", marker.get(null));
        assertEquals("abcdefg...", clip.invoke(null, "abcdefghijklmnop", 10));
        assertEquals("short", clip.invoke(null, "short", 10));
        assertFalse(Arrays.stream(StringUtils.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("clip")),
            "StringUtils still declares clip");
        assertFalse(Arrays.stream(StringUtils.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("CLIP_MARKER")),
            "StringUtils still declares CLIP_MARKER");
    }

    @Test
    void everyCallerStillClips() throws ReflectiveOperationException {
        assertEquals("abcdefg...", StringUtils.clipToTen("abcdefghijklmnop"));
        assertEquals("abcdefghi...", call("org.apache.commons.lang3.builder.ClipStyle", "value", "abcdefghijklmnop"));
        assertEquals(Arrays.asList("abcdefghi...", "fits"),
            call("org.apache.commons.lang3.builder.ClipStyle", "values", Arrays.asList("abcdefghijklmnop", "fits")));
        final Method line = Class.forName("org.apache.commons.lang3.ClipReport").getDeclaredMethod("line", String.class, int.class);
        line.setAccessible(true);
        assertEquals("abcdefg... (clipped)", line.invoke(null, "abcdefghijklmnop", 10));
        assertEquals("short", line.invoke(null, "short", 10));
    }

    private static Object call(final String type, final String name, final Object arg) throws ReflectiveOperationException {
        final Method method = Arrays.stream(Class.forName(type).getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
            .orElseThrow(NoSuchMethodException::new);
        method.setAccessible(true);
        return method.invoke(null, arg);
    }

}
