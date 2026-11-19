package org.u80dc.jsqlint.commons.compiler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 验证超大累计日志、有序尾部和截断边界，不分配与总输出量等大的缓冲。 */
class BuildLogTest {
    @Test void preservesSmallUtf8OutputIncludingSingleByteAndOffsetWrites() throws Exception {
        var log = new BuildLog(64);
        assertEquals("", log.text());
        log.write('[');
        byte[] content = "xx构建失败xx".getBytes(StandardCharsets.UTF_8);
        log.write(content, 2, content.length - 4);
        log.write(']');
        assertEquals("[构建失败]", log.text());
    }

    @Test void preservesExactCapacityAndRejectsInvalidWritesWithoutChangingOutput() throws Exception {
        var log = new BuildLog(4);
        log.write("1234".getBytes(StandardCharsets.UTF_8));
        log.write(new byte[0]);
        assertEquals("1234", log.text());
        assertThrows(IndexOutOfBoundsException.class, () -> log.write(new byte[2], 1, 2));
        assertEquals("1234", log.text());
        assertThrows(IllegalArgumentException.class, () -> new BuildLog(0));
    }

    @Test void keepsOrderedTailAcrossWrappedBulkAndSingleByteWrites() throws Exception {
        var log = new BuildLog(8);
        log.write("123456".getBytes(StandardCharsets.UTF_8));
        log.write("789AB".getBytes(StandardCharsets.UTF_8));
        log.write('C');
        assertEquals("[Earlier build output truncated; showing last 8 bytes]\n56789ABC", log.text());
        log.write("DEF".getBytes(StandardCharsets.UTF_8));
        assertTrue(log.text().endsWith("89ABCDEF"));
    }

    @Test void retainsFailureAtEndOfAnOversizedWriteAndMarksTruncation() throws Exception {
        var log = new BuildLog(8);
        log.write("earlier output: FAILED".getBytes(StandardCharsets.UTF_8));
        assertEquals("[Earlier build output truncated; showing last 8 bytes]\n: FAILED", log.text());
        log.write("12345678".getBytes(StandardCharsets.UTF_8));
        assertTrue(log.text().endsWith("12345678"));
        assertTrue(log.text().startsWith("[Earlier build output truncated;"));
    }

    @Test void truncatesAtUtf8CharacterBoundaries() throws Exception {
        var log = new BuildLog(8);
        log.write("ABC中文错误".getBytes(StandardCharsets.UTF_8));
        assertEquals("[Earlier build output truncated; showing last 8 bytes]\n错误", log.text());
        assertFalse(log.text().contains("\ufffd"));
    }

    @Test void handlesMoreThanTwoGigabytesOfCumulativeOutputWithBoundedDiagnostics() throws Exception {
        var log = new BuildLog();
        byte[] chunk = new byte[1024 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        // 累计输出超过 Integer.MAX_VALUE，实际只分配一个可复用的 1 MiB 输入块。
        for (int i = 0; i < 2049; i++) log.write(chunk);
        String failure = "\n[ERROR] Maven compilation failed: 编译错误\n";
        log.write(failure.getBytes(StandardCharsets.UTF_8));
        String text = assertDoesNotThrow(log::text);
        assertTrue(text.startsWith("[Earlier build output truncated;"));
        assertTrue(text.endsWith(failure));
        assertTrue(text.length() < 64 * 1024 + 100);
    }
}
