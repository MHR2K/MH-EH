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
                // 对于 SMB 和其他非文件流，复制到临时文件以确保流可重复使用
                java.io.File tempFile = null;
                try {
                    tempFile = java.io.File.createTempFile("img_", null, 
                        com.hippo.ehviewer.EhApplication.getInstance().getCacheDir());
                    
                    // 复制流到临时文件，使用更大的缓冲区以提高效率
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[65536]; // 64KB 缓冲区
                        int read;
                        int totalRead = 0;
                        while ((read = rawIs.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                            totalRead += read;
                        }
                        // 检查是否读取了数据
                        if (totalRead == 0) {
                            return null;
                        }
                    }
                    
                    // 使用临时文件中的数据
                    try (FileInputStream fis = new FileInputStream(tempFile)) {
                        return Image.decode(fis, hardware);
                    }
                } finally {
                    // 确保临时文件被删除
                    if (tempFile != null) {
                        if (!tempFile.delete()) {
                            tempFile.deleteOnExit();
                        }
                    }
                }
            }
        } catch (OutOfMemoryError e) {
            Analytics.recordException(e);
            return null;
        } catch (IOException e) {
            // 记录 IO 异常以便调试
            Analytics.recordException(e);
            return null;
        } catch (Exception e) {
            // 捕获其他异常（如图片格式不支持等）
            Analytics.recordException(e);
            return null;
        } finally {
            try {
                isPipe.close();
            } catch (Exception e) {
                // 忽略关闭异常
                Analytics.recordException(e);
            }
            try {
                isPipe.release();
            } catch (Exception e) {
                // 忽略释放异常
                Analytics.recordException(e);
            }
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
