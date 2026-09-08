package com.hippo.ehviewer.smb;

import androidx.annotation.Keep;

/**
 * JNI-facing bridge for libarchive stream I/O.
 * Native code calls {@link #nativeRead(int)} and {@link #nativeSeek(long, int)}
 * to read/seek data from the underlying {@link ArchiveByteSource}.
 *
 * Must be annotated with @Keep to survive R8 shrinking.
 */
@Keep
public class ArchiveStreamBridge {
    private final ArchiveByteSource source;
    private long position = 0;
    private volatile boolean closed = false;

    public ArchiveStreamBridge(ArchiveByteSource source) {
        this.source = source;
    }

    /**
     * Called from JNI via GetMethodID("nativeRead", "(I)[B").
     * Reads up to maxLen bytes at the current position.
     * @return byte array with data, empty array on EOF, throws on error.
     */
    @Keep
    public synchronized byte[] nativeRead(int maxLen) {
        if (closed) return new byte[0];

        byte[] buf = new byte[maxLen];
        int bytesRead = source.readAt(position, buf, 0, maxLen);
        if (bytesRead <= 0) {
            return new byte[0]; // EOF
        }
        position += bytesRead;

        // Trim to actual bytes read
        if (bytesRead < maxLen) {
            byte[] trimmed = new byte[bytesRead];
            System.arraycopy(buf, 0, trimmed, 0, bytesRead);
            return trimmed;
        }
        return buf;
    }

    /**
     * Called from JNI via GetMethodID("nativeSeek", "(JI)J").
     * Seeks to a position in the stream.
     * @param offset seek offset
     * @param whence SEEK_SET (0), SEEK_CUR (1), SEEK_END (2)
     * @return new absolute position, or -1 on failure
     */
    @Keep
    public synchronized long nativeSeek(long offset, int whence) {
        if (closed) return -1;

        long newPos;
        switch (whence) {
            case 0: // SEEK_SET
                newPos = offset;
                break;
            case 1: // SEEK_CUR
                newPos = position + offset;
                break;
            case 2: // SEEK_END
                newPos = source.getSize() + offset;
                break;
            default:
                return -1;
        }

        if (newPos < 0 || newPos > source.getSize()) {
            return -1;
        }

        position = newPos;
        return newPos;
    }

    /**
     * Check if the bridge has a terminal failure that should be thrown after native returns.
     * This is used to surface Java exceptions that were swallowed by JNI ExceptionClear.
     */
    public void throwIfTerminalFailure() {
        // No-op for now; can be extended for RemoteRangeNotSupportedException
    }

    public void close() {
        closed = true;
        try {
            source.close();
        } catch (Exception ignored) {}
    }

    public boolean isClosed() {
        return closed;
    }

    public long getPosition() {
        return position;
    }

    public ArchiveByteSource getSource() {
        return source;
    }
}
