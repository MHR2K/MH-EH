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

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.util.ReadableTime;
import java.io.File;

public class ExportDataPreference extends TaskPreference {

  public ExportDataPreference(Context context) {
    super(context);
  }

  public ExportDataPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ExportDataPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @NonNull
  @Override
  protected Task onCreateTask() {
    return new ExportDataTask(getContext());
  }

  private static class ExportDataTask extends Task {

    public ExportDataTask(@NonNull Context context) {
      super(context);
    }

    @Override
    protected Object doInBackground(Void... voids) {
      // 确保应用完全初始化
      try {
        if (!((com.hippo.ehviewer.EhApplication)getApplication()).isInitialized()) {
          // 等待应用初始化完成，最多等待3秒
          android.util.Log.d("ExportDataTask", "应用尚未初始化，等待初始化完成");
          for (int i = 0; i < 6; i++) {
            Thread.sleep(500);
            if (((com.hippo.ehviewer.EhApplication)getApplication()).isInitialized()) {
              android.util.Log.d("ExportDataTask", "应用已完成初始化");
              break;
            }
          }
        }
      } catch (Exception e) {
        android.util.Log.e("ExportDataTask", "等待应用初始化时出错", e);
      }
      
      // 确保数据库已初始化
      if (!EhDB.isInitialized()) {
        android.util.Log.d("ExportDataTask", "数据库尚未初始化，等待数据库初始化完成");
        // 等待数据库初始化完成，最多等待5秒
        if (!EhDB.waitForInitialization(5000)) {
          // 如果超时仍未初始化，尝试手动初始化
          android.util.Log.d("ExportDataTask", "等待超时，尝试手动初始化数据库");
          EhDB.initialize(getApplication());
          
          if (!EhDB.isInitialized()) {
            // 数据库初始化失败
            android.util.Log.e("ExportDataTask", "数据库初始化失败");
            return null;
          }
        }
        android.util.Log.d("ExportDataTask", "数据库已初始化完成");
      }
      
      File dir = AppConfig.getExternalDataDir();
      if (dir != null) {
        File file = new File(dir, ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".db");
        android.util.Log.d("ExportDataTask", "开始导出数据库到：" + file.getAbsolutePath());
        if (EhDB.exportDB(getApplication(), file)) {
          android.util.Log.d("ExportDataTask", "数据库导出成功");
          return file;
        } else {
          android.util.Log.e("ExportDataTask", "数据库导出失败");
        }
      }
      return null;
    }

    @Override
    protected void onPostExecute(Object o) {
      Toast.makeText(getApplication(),
          (o instanceof File)
              ? GetText.getString(R.string.settings_advanced_export_data_to, ((File) o).getPath())
              : GetText.getString(R.string.settings_advanced_export_data_failed),
          Toast.LENGTH_SHORT).show();
      super.onPostExecute(o);
    }
  }
}
