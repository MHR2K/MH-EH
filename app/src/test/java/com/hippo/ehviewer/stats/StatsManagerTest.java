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

package com.hippo.ehviewer.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.ui.dialog.ReadingRecordsDialog;

import org.junit.Test;

public class StatsManagerTest {

    @Test
    public void testFormatDuration() {
        assertEquals("0m", StatsManager.formatDuration(0));
        assertEquals("0m", StatsManager.formatDuration(-10));
        assertEquals("0m", StatsManager.formatDuration(30));
        assertEquals("1m", StatsManager.formatDuration(60));
        assertEquals("1m", StatsManager.formatDuration(90));
        assertEquals("2m", StatsManager.formatDuration(120));
        assertEquals("1h 0m", StatsManager.formatDuration(3600));
        assertEquals("1h 1m", StatsManager.formatDuration(3661));
        assertEquals("2h 30m", StatsManager.formatDuration(9000));
    }

    @Test
    public void testFormatDetailedDuration() {
        assertEquals("< 1s", ReadingRecordsDialog.formatDetailedDuration(0));
        assertEquals("< 1s", ReadingRecordsDialog.formatDetailedDuration(-5));
        assertEquals("35s", ReadingRecordsDialog.formatDetailedDuration(35));
        assertEquals("1m 0s", ReadingRecordsDialog.formatDetailedDuration(60));
        assertEquals("2m 15s", ReadingRecordsDialog.formatDetailedDuration(135));
        assertEquals("1h 0m", ReadingRecordsDialog.formatDetailedDuration(3600));
        assertEquals("2h 15m", ReadingRecordsDialog.formatDetailedDuration(8100));
    }

    @Test
    public void testReadingRecordEntity() {
        long now = System.currentTimeMillis();
        ReadingRecord record = new ReadingRecord(123456L, "Test Title", now - 60000, now, 60, 1, 30, 30, true);
        assertEquals(123456L, record.gid);
        assertEquals("Test Title", record.title);
        assertEquals(now - 60000, record.startTime);
        assertEquals(now, record.endTime);
        assertEquals(60, record.durationSeconds);
        assertEquals(1, record.pageStart);
        assertEquals(30, record.pageEnd);
        assertEquals(30, record.totalPages);
        assertTrue(record.completed);
    }
}
