package org.u80dc.jsqlint.commons.compiler;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 构建 stdout/stderr 共用的有界 UTF-8 日志，仅保留末尾诊断，避免失败处理复制超大输出。 */
final class BuildLog extends OutputStream {
    private static final int CAPACITY = 64 * 1024;
    private final byte[] buffer;
    private int start;
    private int size;
    private boolean truncated;

    BuildLog() { this(CAPACITY); }

    BuildLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Build log capacity must be positive");
        buffer = new byte[capacity];
    }

    @Override
    public synchronized void write(int value) {
        if (size < buffer.length) {
            buffer[(start + size) % buffer.length] = (byte) value;
            size++;
        } else {
            buffer[start] = (byte) value;
            start = (start + 1) % buffer.length;
            truncated = true;
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length >= buffer.length) {
            truncated |= size > 0 || length > buffer.length;
            System.arraycopy(bytes, offset + length - buffer.length, buffer, 0, buffer.length);
            start = 0;
            size = buffer.length;
            return;
        }
        int tail = (start + size) % buffer.length;
        int first = Math.min(length, buffer.length - tail);
        System.arraycopy(bytes, offset, buffer, tail, first);
        System.arraycopy(bytes, offset + first, buffer, 0, length - first);
        int overflow = Math.max(0, size + length - buffer.length);
        if (overflow > 0) {
            start = (start + overflow) % buffer.length;
            truncated = true;
        }
        size = Math.min(buffer.length, size + length);
    }

    synchronized String text() {
        byte[] bytes = new byte[size];
        int first = Math.min(size, buffer.length - start);
        System.arraycopy(buffer, start, bytes, 0, first);
        System.arraycopy(buffer, 0, bytes, first, size - first);
        // 环形缓冲可能从 UTF-8 字符中间截断，跳过开头残留的续字节。
        int offset = 0;
        if (truncated) while (offset < size && (bytes[offset] & 0xc0) == 0x80) offset++;
        String retained = new String(bytes, offset, size - offset, StandardCharsets.UTF_8);
        return truncated ? "[Earlier build output truncated; showing last " + buffer.length + " bytes]\n" + retained : retained;
    }
}
