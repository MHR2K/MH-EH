package com.hippo.ehviewer.spider;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.database.MSQLiteBuilder;
import com.hippo.util.SqlUtils;

import org.json.JSONObject;

class SpiderInfoDatabase {

	private static final String TABLE_NAME = "SPIDER_INFO";
	private static final String COLUMN_GID = "GID";
	private static final String COLUMN_TOKEN = "TOKEN";
	private static final String COLUMN_START_PAGE = "START_PAGE";
	private static final String COLUMN_PAGES = "PAGES";
	private static final String COLUMN_PREVIEW_PAGES = "PREVIEW_PAGES";
	private static final String COLUMN_PREVIEW_PER_PAGE = "PREVIEW_PER_PAGE";
	private static final String COLUMN_PTOKEN_JSON = "PTOKEN_JSON";
	private static final String COLUMN_UPDATED_AT = "UPDATED_AT";
	private static final String COLUMN_SOURCE = "SOURCE";

	private static final int DB_VERSION = 1;
	private static final String DB_NAME = "spider_info.db";

	private final SQLiteOpenHelper helper;
	private final SQLiteDatabase db;

	SpiderInfoDatabase(@NonNull Context context) {
		helper = new MSQLiteBuilder()
				.version(DB_VERSION)
				.createTable(TABLE_NAME, COLUMN_GID, long.class)
				.insertColumn(TABLE_NAME, COLUMN_TOKEN, String.class)
				.insertColumn(TABLE_NAME, COLUMN_START_PAGE, int.class)
				.insertColumn(TABLE_NAME, COLUMN_PAGES, int.class)
				.insertColumn(TABLE_NAME, COLUMN_PREVIEW_PAGES, int.class)
				.insertColumn(TABLE_NAME, COLUMN_PREVIEW_PER_PAGE, int.class)
				.insertColumn(TABLE_NAME, COLUMN_PTOKEN_JSON, String.class)
				.insertColumn(TABLE_NAME, COLUMN_UPDATED_AT, long.class)
				.insertColumn(TABLE_NAME, COLUMN_SOURCE, String.class)
				.build(context.getApplicationContext(), DB_NAME, DB_VERSION);
		db = helper.getWritableDatabase();
	}

	@Nullable
	synchronized SpiderInfo get(long gid) {
		Cursor cursor = null;
		try {
			cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE " + COLUMN_GID + "=?", new String[]{Long.toString(gid)});
			if (!cursor.moveToFirst()) {
				return null;
			}
			SpiderInfo info = new SpiderInfo();
			info.gid = gid;
			info.token = SqlUtils.getString(cursor, COLUMN_TOKEN, null);
			info.startPage = SqlUtils.getInt(cursor, COLUMN_START_PAGE, 0);
			info.pages = SqlUtils.getInt(cursor, COLUMN_PAGES, -1);
			info.previewPages = SqlUtils.getInt(cursor, COLUMN_PREVIEW_PAGES, -1);
			info.previewPerPage = SqlUtils.getInt(cursor, COLUMN_PREVIEW_PER_PAGE, -1);
			info.pTokenMap = parsePTokenMap(SqlUtils.getString(cursor, COLUMN_PTOKEN_JSON, "{}"));
			return info;
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
	}

	synchronized void upsert(@NonNull SpiderInfo info, @NonNull String source) {
		ContentValues values = new ContentValues();
		values.put(COLUMN_GID, info.gid);
		values.put(COLUMN_TOKEN, info.token);
		values.put(COLUMN_START_PAGE, info.startPage);
		values.put(COLUMN_PAGES, info.pages);
		// 以下三个字段不再写入数据库，减少存储开销
		// values.put(COLUMN_PREVIEW_PAGES, info.previewPages);
		// values.put(COLUMN_PREVIEW_PER_PAGE, info.previewPerPage);
		// values.put(COLUMN_PTOKEN_JSON, toJson(info.pTokenMap));
		values.put(COLUMN_UPDATED_AT, System.currentTimeMillis());
		values.put(COLUMN_SOURCE, source);
		db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
	}

	synchronized void delete(long gid) {
		db.delete(TABLE_NAME, COLUMN_GID + "=?", new String[]{Long.toString(gid)});
	}

	private static String toJson(@Nullable SparseArray<String> map) {
		if (map == null || map.size() == 0) {
			return "{}";
		}
		try {
			JSONObject json = new JSONObject();
			for (int i = 0, n = map.size(); i < n; i++) {
				json.put(Integer.toString(map.keyAt(i)), map.valueAt(i));
			}
			return json.toString();
		} catch (Exception e) {
			return "{}";
		}
	}

	private static SparseArray<String> parsePTokenMap(@Nullable String json) {
		SparseArray<String> array = new SparseArray<>();
		if (json == null || json.isEmpty()) {
			return array;
		}
		try {
			JSONObject obj = new JSONObject(json);
			for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
				String key = it.next();
				String value = obj.optString(key, null);
				if (value != null) {
					array.put(Integer.parseInt(key), value);
				}
			}
		} catch (Exception ignore) {
			// ignore parse errors
		}
		return array;
	}
}
