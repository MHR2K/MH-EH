/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BackupManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testZipDetectionAndIntegrity() throws IOException {
        File zipFile = tempFolder.newFile("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("eh.db"));
            zos.write("database content".getBytes());
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry entry = zf.getEntry("eh.db");
            assertTrue(entry != null);
            try (InputStream is = zf.getInputStream(entry)) {
                byte[] buffer = new byte[64];
                int read = is.read(buffer);
                assertEquals("database content", new String(buffer, 0, read));
            }
        }
    }


    @Test
    public void testDeleteDirectory() throws IOException {
        File dir = tempFolder.newFolder("dir_to_delete");
        File child1 = new File(dir, "child1.txt");
        assertTrue(child1.createNewFile());
        File subDir = new File(dir, "subdir");
        assertTrue(subDir.mkdir());
        File child2 = new File(subDir, "child2.txt");
        assertTrue(child2.createNewFile());

        BackupManager.deleteDirectory(dir);
        assertFalse(dir.exists());
    }
}
