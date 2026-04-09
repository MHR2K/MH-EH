#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Patch SpiderDen.java to add CBZ caching support
"""

import os

# Read the file
file_path = 'app/src/main/java/com/hippo/ehviewer/spider/SpiderDen.java'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find and replace Step 1: gid.cbz reading logic
old_step1 = '''        // Step 1: 尝试 <gid>.cbz （若 base 是目录）
        if (!normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            String gidCbz = mGid + ".cbz";
            String relGidCbz = normBase.isEmpty() ? gidCbz : (normBase + "\\\\" + gidCbz);
            Client.Target gidCbzTarget = new Client.Target(authority, share, relGidCbz);
            try {
                java.io.InputStream probe = Client.INSTANCE.openInputStream(gidCbzTarget);
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz success: gid=" + mGid + ", path=" + relGidCbz);
                }
                // 包装为 Zip 流读取所需条目
                final String relCbzFinal = relGidCbz;
                return new InputStreamPipe() {
                    private java.io.InputStream mBase;
                    private ZipInputStream mZis;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        mBase = Client.INSTANCE.openInputStream(new Client.Target(authority, share, relCbzFinal));
                        mZis = new ZipInputStream(mBase);
                        ZipEntry entry;
                        while ((entry = mZis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            String en = entry.getName();
                            if (en == null) continue;
                            for (String ext : exts) {
                                String expect = generateImageFilename(index, ext);
                                if (expect.equalsIgnoreCase(en)) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                    }
                                    return mZis;
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz miss entry: gid=" + mGid + ", index=" + (index+1) + ", path=" + relCbzFinal);
                        }
                        close();
                        throw new IOException("Entry not found in remote gid.cbz for index=" + index);
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                        mZis = null; mBase = null;
                    }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz not found: gid=" + mGid + ", path=" + relGidCbz);
                }
            }
        }'''

new_step1 = '''        // Step 1: 尝试 <gid>.cbz （若 base 是目录）
        if (!normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            String gidCbz = mGid + ".cbz";
            String relGidCbz = normBase.isEmpty() ? gidCbz : (normBase + "\\\\" + gidCbz);
            Client.Target gidCbzTarget = new Client.Target(authority, share, relGidCbz);
            
            // 尝试从本地缓存获取 CBZ
            try {
                java.io.FileInputStream cachedStream = CbzCacheManager.INSTANCE.getCachedInputStream(authority, share, relGidCbz);
                if (cachedStream != null) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SpiderDen", "SMB gid.cbz cache hit: gid=" + mGid + ", path=" + relGidCbz);
                    }
                    // 从缓存的 CBZ 解压
                    final java.io.FileInputStream fis = cachedStream;
                    return new InputStreamPipe() {
                        private java.io.FileInputStream mFis;
                        private ZipInputStream mZis;
                        @Override public void obtain() { /* no-op */ }
                        @Override public void release() { /* no-op */ }
                        @Override public java.io.InputStream open() throws IOException {
                            mFis = fis;
                            mZis = new ZipInputStream(mFis);
                            ZipEntry entry;
                            while ((entry = mZis.getNextEntry()) != null) {
                                if (entry.isDirectory()) continue;
                                String en = entry.getName();
                                if (en == null) continue;
                                for (String ext : exts) {
                                    String expect = generateImageFilename(index, ext);
                                    if (expect.equalsIgnoreCase(en)) {
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d("SpiderDen", "SMB gid.cbz cache hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                        }
                                        return mZis;
                                    }
                                }
                            }
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SpiderDen", "SMB gid.cbz cache miss entry: gid=" + mGid + ", index=" + (index+1));
                            }
                            close();
                            throw new IOException("Entry not found in cached CBZ for index=" + index);
                        }
                        @Override public void close() {
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mFis);
                            mZis = null; mFis = null;
                        }
                    };
                }
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "Failed to get cached CBZ, falling back to SMB: " + e);
                }
            }
            
            // 缓存未命中，从 SMB 读取并异步缓存
            try {
                java.io.InputStream probe = Client.INSTANCE.openInputStream(gidCbzTarget);
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz success: gid=" + mGid + ", path=" + relGidCbz);
                }
                // 异步缓存 CBZ
                final String relCbzFinal = relGidCbz;
                final java.io.InputStream probeFinal = probe;
                CbzCacheManager.INSTANCE.cacheCbzAsync(authority, share, relCbzFinal, probeFinal);
                
                // 同步从 SMB 读取并返回
                return new InputStreamPipe() {
                    private java.io.InputStream mBase;
                    private ZipInputStream mZis;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        mBase = Client.INSTANCE.openInputStream(new Client.Target(authority, share, relCbzFinal));
                        mZis = new ZipInputStream(mBase);
                        ZipEntry entry;
                        while ((entry = mZis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            String en = entry.getName();
                            if (en == null) continue;
                            for (String ext : exts) {
                                String expect = generateImageFilename(index, ext);
                                if (expect.equalsIgnoreCase(en)) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                    }
                                    return mZis;
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz miss entry: gid=" + mGid + ", index=" + (index+1) + ", path=" + relCbzFinal);
                        }
                        close();
                        throw new IOException("Entry not found in remote gid.cbz for index=" + index);
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                        mZis = null; mBase = null;
                    }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz not found: gid=" + mGid + ", path=" + relGidCbz);
                }
            }
        }'''

# Check if old code exists
if old_step1 in content:
    content = content.replace(old_step1, new_step1)
    print("Step 1 replaced successfully!")
else:
    print("Step 1 pattern not found, trying simpler approach...")
    # Try a simpler approach - just add cache check before the existing code
    if '// Step 1: 尝试 <gid>.cbz' in content:
        print("Found Step 1 comment")
    else:
        print("Step 1 comment not found either")

# Write back
with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Done!")
