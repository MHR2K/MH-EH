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
}
