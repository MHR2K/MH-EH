package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)

external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int

/**
 * Request cooperative abort of in-flight native archive work (stream pread / libarchive
 * callbacks / extract loops). Cleared when a new native session opens.
 */
external fun requestArchiveAbort()

/**
 * Open archive via [ArchiveStreamBridge] (seek/read callbacks).
 * Does not mmap the full file — for remote ZIP/CBZ stream open.
 *
 * @param coverOnly if true, only index the cover page (natural-first ZIP entry).
 * @param progressiveTar unused for CBZ, kept for API compatibility.
 * @param maxScanBytes non-ZIP scan budget. `0` = unlimited. ZIP EOCD+CD is always uncapped.
 */
external fun openArchiveStream(
    bridge: Any,
    size: Long,
    sortEntries: Boolean,
    coverOnly: Boolean,
    progressiveTar: Boolean,
    maxScanBytes: Long,
): Int

/** Bytes read through the stream bridge for the active session. */
external fun getStreamBytesRead(): Long

/** True when [openArchiveStream] hit [maxScanBytes]. */
external fun isArchiveScanLimited(): Boolean

/** True when stream open confirmed a container with zero playable images. */
external fun isStreamIndexFinishedEmpty(): Boolean

/** Continue progressive TAR header walk; returns total listed count. No-op for ZIP. */
external fun continueStreamTarIndex(maxNew: Int): Int

/** True when ZIP/full open finished indexing. */
external fun isStreamIndexComplete(): Boolean

/**
 * Install a pre-parsed stream index (from disk cache) and bind [bridge] for extract.
 * Skips ZIP EOCD/CD walk. Arrays are parallel, length = page count.
 * @return entry count on success, 0 on failure.
 */
external fun loadStreamIndex(
    bridge: Any,
    archiveSize: Long,
    offsets: LongArray,
    uncSizes: LongArray,
    compSizes: LongArray,
    methods: IntArray,
    names: Array<String>,
    isTar: Boolean,
): Int

/** Stream ZIP index: member local-header offset; -1 if N/A. */
external fun getStreamMemberOffset(index: Int): Long

/** Stream ZIP index: compressed member length for readahead warm; -1 if N/A. */
external fun getStreamMemberLength(index: Int): Long

/** Uncompressed member size (decode buffer). -1 if N/A. */
external fun getStreamMemberUncSize(index: Int): Long

/** ZIP method (0/8). -1 if N/A. */
external fun getStreamMemberMethod(index: Int): Int

/** True when the active stream session is a TAR header index (not ZIP CD). */
external fun isStreamTarIndex(): Boolean

external fun extractToByteBuffer(index: Int): ByteBuffer?

/**
 * Stream-aware extract: uses the provided [bridge] directly for seek/read
 * instead of the global bridge. Thread-safe when each caller passes its own bridge.
 */
external fun extractStreamToByteBuffer(index: Int, bridge: Any): ByteBuffer?

external fun extractToFd(index: Int, fd: Int): Boolean

external fun getExtension(index: Int): String

external fun needPassword(): Boolean

external fun providePassword(str: String): Boolean

external fun closeArchive()

external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)
