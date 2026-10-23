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

package com.hippo.ehviewer.ui.scene.gallery.list

import android.app.DatePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.subscription.SubscriptionDigestHolder
import com.hippo.ehviewer.subscription.SubscriptionDigestService
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.scene.Announcer
import com.hippo.scene.StageActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SubscriptionDigestDialog(private val context: Context) {

    fun show() {
        if (SubscriptionDigestHolder.isRunning) {
            showRunningDialog()
            return
        }

        showDateSelectionDialog()
    }

    private fun showRunningDialog() {
        val current = SubscriptionDigestHolder.progressCurrent
        val total = SubscriptionDigestHolder.progressTotal
        val date = SubscriptionDigestHolder.targetDate
        val message = context.getString(R.string.subscription_digest_task_running, date, current, total)

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.subscription_digest_running)
            .setMessage(message)
            .setPositiveButton(R.string.subscription_digest_wait_background, null)
            .setNegativeButton(R.string.subscription_digest_cancel) { _, _ ->
                SubscriptionDigestService.cancel(context)
                Toast.makeText(context, R.string.subscription_digest_cancelled, Toast.LENGTH_SHORT).show()
            }

        builder.setNeutralButton(R.string.subscription_digest_view) { _, _ ->
            openDigestScene()
        }

        builder.show()
    }

    private fun showDateSelectionDialog() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = dateFormat.format(Date())

        val cal = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
        val yesterday = dateFormat.format(cal.time)

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val hasPrevious = SubscriptionDigestHolder.allList.isNotEmpty()
        if (hasPrevious) {
            options.add(
                context.getString(
                    R.string.subscription_digest_view_last,
                    SubscriptionDigestHolder.targetDate,
                    SubscriptionDigestHolder.allList.size
                )
            )
            actions.add { openDigestScene() }
        }

        options.add(context.getString(R.string.subscription_digest_today, today))
        actions.add { confirmAndStart(today) }

        options.add(context.getString(R.string.subscription_digest_yesterday, yesterday))
        actions.add { confirmAndStart(yesterday) }

        options.add(context.getString(R.string.subscription_digest_custom_date))
        actions.add { pickCustomDate() }

        AlertDialog.Builder(context)
            .setTitle(R.string.subscription_digest_dialog_title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which in 0 until actions.size) {
                    actions[which].invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickCustomDate() {
        val calendar = Calendar.getInstance()
        val datePicker = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                val selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedCal.time)
                confirmAndStart(selectedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePicker.datePicker.maxDate = System.currentTimeMillis()
        datePicker.show()
    }

    private fun confirmAndStart(date: String) {
        AlertDialog.Builder(context)
            .setTitle("${context.getString(R.string.subscription_digest_start)} ($date)")
            .setMessage(R.string.subscription_digest_hint)
            .setPositiveButton(R.string.subscription_digest_start) { _, _ ->
                SubscriptionDigestService.start(context, date)
                Toast.makeText(
                    context,
                    "${context.getString(R.string.subscription_digest_running)} ($date)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDigestScene() {
        if (context is StageActivity) {
            context.startScene(Announcer(SubscriptionDigestScene::class.java))
        }
    }
}
