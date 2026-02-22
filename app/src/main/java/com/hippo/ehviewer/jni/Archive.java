/*
 * Copyright 2022-2024 Tarsin Norbin
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
package com.hippo.ehviewer.jni;

import java.nio.ByteBuffer;

public class Archive {
    static {
        System.loadLibrary("ehviewer");
    }

    public static native void releaseByteBuffer(ByteBuffer buffer);

    public static native int openArchive(int fd, long size, boolean sortEntries);

    public static native ByteBuffer extractToByteBuffer(int index);

    public static native boolean extractToFd(int index, int fd);

    public static native String getExtension(int index);

    public static native boolean needPassword();

    public static native boolean providePassword(String str);

    public static native void closeArchive();

    public static native void archiveFdBatch(int[] fdBatch, String[] names, int arcFd, int size);
}
