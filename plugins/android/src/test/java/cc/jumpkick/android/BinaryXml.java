// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The smallest {@code AndroidManifest.xml} apksig will accept: Android's binary XML (AXML) for a
 * single empty {@code <manifest/>} element, in 112 bytes.
 *
 * <p>It has to be real. apksig refuses to sign an APK whose manifest it cannot parse — it reads the
 * minimum platform version out of it to decide which signature schemes apply — so a fixture with
 * the text {@code "<manifest/>"} in it fails before any of the packager's own behaviour is reached.
 * aapt2 is the only thing that produces AXML in a real build, and it is a fetched per-OS native
 * binary; requiring it here would make every APK test an integration test.
 *
 * <p>Format (frameworks/base {@code ResourceTypes.h}): a {@code RES_XML_TYPE} chunk wrapping a
 * string pool and one start/end element pair. No {@code <uses-sdk>}, so the minimum platform
 * version parses as 1 — which is what a signing test wants, since it applies every scheme.
 */
final class BinaryXml {

    private static final short RES_XML_TYPE = 0x0003;
    private static final short RES_STRING_POOL_TYPE = 0x0001;
    private static final short RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final short RES_XML_END_ELEMENT_TYPE = 0x0103;

    /** {@code UTF8_FLAG} — the pool encoding aapt2 emits. */
    private static final int UTF8_FLAG = 0x0100;

    private static final int NO_ENTRY = -1;

    private BinaryXml() {}

    /** A parseable binary {@code AndroidManifest.xml} holding one empty {@code <manifest/>}. */
    static byte[] manifest() {
        byte[] pool = stringPool("manifest");
        byte[] start = startElement(0);
        byte[] end = endElement(0);
        int total = 8 + pool.length + start.length + end.length;
        ByteBuffer out = buffer(total);
        out.putShort(RES_XML_TYPE);
        out.putShort((short) 8);
        out.putInt(total);
        out.put(pool);
        out.put(start);
        out.put(end);
        return out.array();
    }

    private static byte[] stringPool(String... strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            byte[] utf8 = strings[i].getBytes(StandardCharsets.UTF_8);
            // A UTF-8 pool entry is <char length><byte length><bytes><NUL>; both lengths take a
            // second byte above 0x7f, which nothing here reaches.
            data.write(strings[i].length());
            data.write(utf8.length);
            data.write(utf8, 0, utf8.length);
            data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        byte[] blob = data.toByteArray();
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.length;
        ByteBuffer out = buffer(stringsStart + blob.length);
        out.putShort(RES_STRING_POOL_TYPE);
        out.putShort((short) headerSize);
        out.putInt(stringsStart + blob.length);
        out.putInt(strings.length);
        out.putInt(0); // no styles
        out.putInt(UTF8_FLAG);
        out.putInt(stringsStart);
        out.putInt(0); // styles start
        for (int offset : offsets) out.putInt(offset);
        out.put(blob);
        return out.array();
    }

    private static byte[] startElement(int nameIndex) {
        ByteBuffer out = buffer(36);
        out.putShort(RES_XML_START_ELEMENT_TYPE);
        out.putShort((short) 16);
        out.putInt(36);
        out.putInt(1); // line number
        out.putInt(NO_ENTRY); // comment
        out.putInt(NO_ENTRY); // namespace
        out.putInt(nameIndex);
        out.putShort((short) 20); // attributeStart
        out.putShort((short) 20); // attributeSize
        out.putShort((short) 0); // attributeCount
        out.putShort((short) 0); // idIndex
        out.putShort((short) 0); // classIndex
        out.putShort((short) 0); // styleIndex
        return out.array();
    }

    private static byte[] endElement(int nameIndex) {
        ByteBuffer out = buffer(24);
        out.putShort(RES_XML_END_ELEMENT_TYPE);
        out.putShort((short) 16);
        out.putInt(24);
        out.putInt(1); // line number
        out.putInt(NO_ENTRY); // comment
        out.putInt(NO_ENTRY); // namespace
        out.putInt(nameIndex);
        return out.array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
