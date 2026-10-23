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

package com.hippo.ehviewer.subscription

import com.hippo.ehviewer.client.data.GalleryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubscriptionDigestHolderTest {

    @Before
    fun setUp() {
        SubscriptionDigestHolder.clear()
    }

    @Test
    fun testInitialState() {
        assertFalse(SubscriptionDigestHolder.isRunning)
        assertFalse(SubscriptionDigestHolder.isCancelled)
        assertEquals(0, SubscriptionDigestHolder.progressCurrent)
        assertEquals(0, SubscriptionDigestHolder.progressTotal)
        assertEquals("", SubscriptionDigestHolder.statusText)
        assertTrue(SubscriptionDigestHolder.allList.isEmpty())
    }

    @Test
    fun testCancelAndClear() {
        SubscriptionDigestHolder.isRunning = true
        SubscriptionDigestHolder.cancel()
        assertTrue(SubscriptionDigestHolder.isCancelled)

        SubscriptionDigestHolder.clear()
        assertFalse(SubscriptionDigestHolder.isRunning)
        assertFalse(SubscriptionDigestHolder.isCancelled)
        assertEquals(0, SubscriptionDigestHolder.allList.size)
    }

    @Test
    fun testListenerCallbacks() {
        var progressCalled = false
        var completeCalled = false
        var errorCalled = false

        val listener = object : SubscriptionDigestHolder.OnProgressListener {
            override fun onProgress(current: Int, total: Int, status: String) {
                progressCalled = true
                assertEquals(5, current)
                assertEquals(10, total)
                assertEquals("Testing", status)
            }

            override fun onComplete(targetDate: String, count: Int) {
                completeCalled = true
                assertEquals("2026-10-23", targetDate)
                assertEquals(42, count)
            }

            override fun onError(error: String) {
                errorCalled = true
                assertEquals("Failed", error)
            }
        }

        SubscriptionDigestHolder.addListener(listener)

        SubscriptionDigestHolder.notifyProgress(5, 10, "Testing")
        assertTrue(progressCalled)

        SubscriptionDigestHolder.notifyComplete("2026-10-23", 42)
        assertTrue(completeCalled)
        assertFalse(SubscriptionDigestHolder.isRunning)

        SubscriptionDigestHolder.notifyError("Failed")
        assertTrue(errorCalled)

        SubscriptionDigestHolder.removeListener(listener)
    }

    @Test
    fun testSortingLogic() {
        val g1 = GalleryInfo().apply {
            gid = 1L
            favoriteCount = 10
            rating = 4.0f
        }
        val g2 = GalleryInfo().apply {
            gid = 2L
            favoriteCount = 50
            rating = 3.5f
        }
        val g3 = GalleryInfo().apply {
            gid = 3L
            favoriteCount = 50
            rating = 4.8f
        }
        val g4 = GalleryInfo().apply {
            gid = 4L
            favoriteCount = -1
            rating = 5.0f
        }

        val list = mutableListOf(g1, g2, g3, g4)
        list.sortWith(
            compareByDescending<GalleryInfo> { it.favoriteCount }
                .thenByDescending { it.rating }
        )

        // g3 (50 fav, 4.8 rating) should be first
        // g2 (50 fav, 3.5 rating) should be second
        // g1 (10 fav, 4.0 rating) should be third
        // g4 (-1 fav, 5.0 rating) should be fourth
        assertEquals(3L, list[0].gid)
        assertEquals(2L, list[1].gid)
        assertEquals(1L, list[2].gid)
        assertEquals(4L, list[3].gid)
    }
}
