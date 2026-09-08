/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Process;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.jni.ArchiveKt;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UniRandomAccessFile;
import com.hippo.lib.yorozuya.thread.PriorityThread;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ArchiveGalleryProvider extends GalleryProvider2 {

    private static final AtomicInteger sIdGenerator = new AtomicInteger();

    private final UniFile file;

    private Thread archiveThread;
    private ExecutorService decodeExecutor;

    private volatile int size = STATE_WAIT;
    private String error;

    private final Stack<Integer> requests = new Stack<>();
    private final AtomicInteger extractingIndex = new AtomicInteger(GalleryPageView.INVALID_INDEX);
    private final LinkedHashMap<Integer, InputStream> streams = new LinkedHashMap<>();
    // 使用ConcurrentHashMap来跟踪正在解码的索引，避免AtomicInteger的竞争问题
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> decodingIndices = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        // Load the native library
        System.loadLibrary("ehviewer");
    }

    public ArchiveGalleryProvider(Context context, Uri uri) {
        file = UniFile.fromUri(context, uri);
    }

    @Override
    public void start() {
        super.start();

        int id = sIdGenerator.incrementAndGet();

        archiveThread = new PriorityThread(
                new ArchiveTask(), "ArchiveTask" + '-' + id, Process.THREAD_PRIORITY_BACKGROUND);
        archiveThread.start();

        // 使用线程池创建2个解码线程
        decodeExecutor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            decodeExecutor.execute(new DecodeTask("DecodeTask" + '-' + id + '-' + i));
        }
    }

    @Override
    public void stop() {
        super.stop();

        // Close the native archive
        try {
            ArchiveKt.closeArchive();
        } catch (Throwable e) {
            e.printStackTrace();
        }

        if (archiveThread != null) {
            archiveThread.interrupt();
            archiveThread = null;
        }
        if (decodeExecutor != null) {
            decodeExecutor.shutdownNow();
            decodeExecutor = null;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    protected void onRequest(int index) {
        boolean inDecodeTask;
        synchronized (streams) {
            inDecodeTask = streams.keySet().contains(index) || decodingIndices.containsKey(index);
        }

        synchronized (requests) {
            boolean inArchiveTask = requests.contains(index) || index == extractingIndex.get();
            if (!inArchiveTask && !inDecodeTask) {
                requests.add(index);
                requests.notify();
            }
        }
        notifyPageWait(index);
    }

    @Override
    protected void onForceRequest(int index) {
        onRequest(index);
    }

    @Override
    protected void onCancelRequest(int index) {
        synchronized (requests) {
            requests.remove(Integer.valueOf(index));
        }
    }

    @Override
    public String getError() {
        return error;
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        try {
            String ext = ArchiveKt.getExtension(index);
            return index + (ext != null ? "." + ext : "");
        } catch (Throwable e) {
            return Integer.toString(index);
        }
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        // TODO
        return false;
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        // TODO
        return null;
    }

    private class ArchiveTask implements Runnable {
        @Override
        public void run() {
            UniRandomAccessFile uraf = null;
            FileDescriptor fd = null;
            
            if (file != null) {
                try {
                    uraf = file.createRandomAccessFile("r");
                    // Try to get FileDescriptor from UniRandomAccessFile
                    if (uraf instanceof java.io.RandomAccessFile) {
                        fd = ((java.io.RandomAccessFile) uraf).getFD();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            
            if (uraf == null || fd == null) {
                size = STATE_ERROR;
                error = GetText.getString(R.string.error_reading_failed);
                notifyDataChanged();
                return;
            }

            try {
                // Get file descriptor int value
                int fdInt = -1;
                try {
                    // Use reflection to get the file descriptor's int
                    java.lang.reflect.Field fdField = FileDescriptor.class.getDeclaredField("fd");
                    fdField.setAccessible(true);
                    fdInt = fdField.getInt(fd);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                
                if (fdInt < 0) {
                    size = STATE_ERROR;
                    error = "Cannot get valid file descriptor";
                    notifyDataChanged();
                    return;
                }
                
                long fileSize = uraf.length();

                // Open archive using native library
                int count = ArchiveKt.openArchive(fdInt, fileSize, true);
                if (count <= 0) {
                    size = STATE_ERROR;
                    error = GetText.getString(R.string.error_invalid_archive);
                    notifyDataChanged();
                    return;
                }

                // Update size and notify changed
                size = count;
                notifyDataChanged();

                while (!Thread.currentThread().isInterrupted()) {
                    int index;
                    synchronized (requests) {
                        if (requests.isEmpty()) {
                            try {
                                requests.wait();
                            } catch (InterruptedException e) {
                                // Interrupted
                                break;
                            }
                            continue;
                        }
                        index = requests.pop();
                        extractingIndex.lazySet(index);
                    }

                    // Check index valid
                    if (index < 0 || index >= size) {
                        extractingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                        notifyPageFailed(index, GetText.getString(R.string.error_out_of_range));
                        continue;
                    }

                    try {
                        // Extract to ByteBuffer using native method
                        ByteBuffer buffer = ArchiveKt.extractToByteBuffer(index);
                        if (buffer != null) {
                            // Create InputStream from ByteBuffer
                            InputStream is = new ByteBufferInputStream(buffer, index);
                            synchronized (streams) {
                                if (streams.get(index) != null) {
                                    continue;
                                }
                                streams.put(index, is);
                                streams.notify();
                            }
                        } else {
                            extractingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                            notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        extractingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                        notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                    } finally {
                        extractingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                size = STATE_ERROR;
                error = e.getMessage();
                notifyDataChanged();
            }
        }
    }

    private class DecodeTask implements Runnable {
        private final String name;
        
        DecodeTask(String name) {
            this.name = name;
        }
        
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                int index;
                InputStream stream;
                synchronized (streams) {
                    if (streams.isEmpty()) {
                        try {
                            streams.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            break;
                        }
                        continue;
                    }

                    java.util.Iterator<java.util.Map.Entry<Integer, InputStream>> iterator = streams.entrySet().iterator();
                    java.util.Map.Entry<Integer, InputStream> entry = iterator.next();
                    iterator.remove();
                    index = entry.getKey();
                    try {
                        stream = entry.getValue();
                    } catch (ClassCastException e) {
                        notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                        decodingIndices.remove(index);
                        return;
                    }
                    // 标记正在解码
                    decodingIndices.put(index, Boolean.TRUE);
                }

                try {
                    Image image = Image.decode(BitmapDrawable.createFromStream(stream, null), false);
                    if (image != null) {
                        notifyPageSucceed(index, image);
                    } else {
                        notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                    notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                } finally {
                    decodingIndices.remove(index);
                }
            }
        }
    }

    /**
     * InputStream wrapper for ByteBuffer
     */
    private static class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buffer;
        private final int index;

        ByteBufferInputStream(ByteBuffer buffer, int index) {
            this.buffer = buffer;
            this.index = index;
        }

        @Override
        public int read() throws IOException {
            if (buffer.hasRemaining()) {
                return buffer.get() & 0xFF;
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int available = buffer.remaining();
            if (available <= 0) {
                return -1;
            }
            int toRead = Math.min(len, available);
            buffer.get(b, off, toRead);
            return toRead;
        }

        @Override
        public int available() throws IOException {
            return buffer.remaining();
        }

        @Override
        public void close() {
            // Release the ByteBuffer back to native
            try {
                ArchiveKt.releaseByteBuffer(buffer);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Simple LinkedHashMap without import
     */
    private static class LinkedHashMap<K, V> extends java.util.HashMap<K, V> {
        @Override
        public V put(K key, V value) {
            return super.put(key, value);
        }

        @Override
        public V get(Object key) {
            return super.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return super.containsKey(key);
        }

        @Override
        public java.util.Set<java.util.Map.Entry<K, V>> entrySet() {
            return super.entrySet();
        }

        @Override
        public boolean isEmpty() {
            return super.isEmpty();
        }
    }
}
