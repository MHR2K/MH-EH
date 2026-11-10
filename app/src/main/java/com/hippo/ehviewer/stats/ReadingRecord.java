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

public class ReadingRecord {
    public long id;
    public long gid;
    public String title;
    public long startTime;
    public long endTime;
    public int durationSeconds;
    public int pageStart;
    public int pageEnd;
    public int totalPages;
    public boolean completed;

    public ReadingRecord() {
    }

    public ReadingRecord(long gid, String title, long startTime, long endTime,
                         int durationSeconds, int pageStart, int pageEnd,
                         int totalPages, boolean completed) {
        this.gid = gid;
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationSeconds = durationSeconds;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.totalPages = totalPages;
        this.completed = completed;
    }
}

