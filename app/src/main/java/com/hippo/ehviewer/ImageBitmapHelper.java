/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.conaco.ValueHelper;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.ImageBitmap;
import com.hippo.streampipe.InputStreamPipe;

import java.io.FileInputStream;
import java.io.IOException;

public class ImageBitmapHelper implements ValueHelper<Image> {

    private static final int MAX_CACHE_SIZE = 512 * 512;

    @Nullable
    @Override
    public Image decode(@NonNull InputStreamPipe isPipe) {
        return decode(isPipe,true);
    }

    @Nullable
    public Image decode(@NonNull InputStreamPipe isPipe,boolean hardware) {
        try {
            isPipe.obtain();
            java.io.InputStream rawIs = isPipe.open();
            
            // 如果是 FileInputStream，直接使用；否则复制到临时文件
            if (rawIs instanceof FileInputStream) {
                return Image.decode((FileInputStream) rawIs, hardware);
            } else {
                // 对于 SMB 和其他非文件流，复制到临时文件
                java.io.File tempFile = java.io.File.createTempFile("img_", null, 
                    com.hippo.ehviewer.EhApplication.getInstance().getCacheDir());
                try {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = rawIs.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                    try (FileInputStream fis = new FileInputStream(tempFile)) {
                        return Image.decode(fis, hardware);
                    }
                } finally {
                    if (!tempFile.delete()) {
                        tempFile.deleteOnExit();
                    }
                }
            }
        } catch (OutOfMemoryError e) {
            Analytics.recordException(e);
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            isPipe.close();
            isPipe.release();
        }
    }

    @Override
    public int sizeOf(@NonNull String key, @NonNull Image value) {
        return value.getWidth() * value.getHeight() * 4 /* value.getByteCount() TODO Update Image */;
    }

//    @Override
//    public void onAddToMemoryCache(@NonNull String key, @NonNull Image value) {
//        value.obtain();
//    }

    @Override
    public void onAddToMemoryCache(@NonNull Image oldValue) {
        oldValue.obtain();
    }

    @Override
    public void onRemoveFromMemoryCache(@NonNull String key, @NonNull Image oldValue) {
        oldValue.release();
    }

    @Override
    public boolean useMemoryCache(@NonNull String key, Image value) {
        if (value != null) {
            return value.getWidth() * value.getHeight() <= MAX_CACHE_SIZE
                    /* value.getByteCount() <= MAX_CACHE_BYTE_COUNT TODO Update Image */;
        } else {
            return true;
        }
    }
}
