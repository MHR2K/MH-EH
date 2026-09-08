# SMB CBZ 流式读取实现总结

## 目标
将 SMB 路径下 CBZ 漫画的读取方式从「先下载整个文件再打开」改为「流式读取：只解析 ZIP Central Directory + 按需解压单个 entry」。

## 当前状态
- ✅ 编译通过，APK 可打包
- ❌ 运行时仍然显示下载进度条（流式读取可能失败，回退到旧逻辑）

## 已完成的改动

### 1. Native 层
**文件**: `app/src/main/cpp/archive.c` (从 LocalViewer 复制)
- stream I/O 回调: `stream_pread`, `stream_read_cb`, `stream_seek_cb`
- ZIP CD 快速索引: `zip_stream_open_from_cd()` - 只读 EOCD + CD (~64-128 KiB)
- ZIP entry 直接解压: `zip_stream_extract_entry()` - 按 local header offset seek + inflate
- cooperative abort: `requestArchiveAbort()`
- 22 种图片扩展名 + macOS junk 过滤

### 2. JNI 桥接
**文件**: `app/src/main/java/com/hippo/ehviewer/jni/Archive.kt` (替代 Archive.java)
- Kotlin 顶层函数，匹配 `ArchiveKt` JNI 前缀
- 新增: `openArchiveStream()`, `requestArchiveAbort()`, `getStreamMemberOffset/Length/UncSize/Method()`

### 3. Java/Kotlin 抽象层 (新建 7 个文件)

| 文件 | 说明 |
|------|------|
| `smb/ArchiveByteSource.kt` | 核心接口: `readAt(offset, buf, off, len)` |
| `smb/ReadAheadArchiveByteSource.kt` | 8 MiB 顺序窗口 + 64 KiB 随机窗口预读 |
| `smb/SmbArchiveByteSource.kt` | SMB 文件句柄包装，自动重连 |
| `smb/ArchiveStreamBridge.java` | JNI 桥接: `nativeRead`/`nativeSeek` 回调 |
| `smb/ArchiveStreamPageCache.kt` | 页面磁盘缓存 + index.json 持久化 |
| `smb/SmbStreamArchiveReader.kt` | 流式读取辅助类 |

### 4. 集成改动

| 文件 | 改动 |
|------|------|
| `Client.kt` | 新增 `openFile()` 方法返回 smbj File 句柄 |
| `SpiderDen.java` | 新增 `tryOpenSmbStreamArchive()`, 在 `openSmbInputStreamPipe()` 开头调用 |
| `EhApplication.java` | 初始化 `ArchiveStreamPageCache` |
| `CMakeLists.txt` | 链接 zlib (`z`) |
| `ArchiveGalleryProvider.java` | 更新 import 为 `ArchiveKt` |

## 新数据流（设计）
```
SMB Server → Client.openFile → SmbArchiveByteSource
  → ReadAheadArchiveByteSource (8 MiB 窗口)
  → ArchiveStreamBridge (JNI) → archive.c: zip_stream_open_from_cd (~128 KiB)
  → extractToByteBuffer(index) → zip_stream_extract_entry → inflate
  → ArchiveStreamPageCache (磁盘缓存) → Image.decode → GalleryView
```

## 待修复问题

### 问题: 运行时仍然显示下载进度条
**原因**: `tryOpenSmbStreamArchive()` 失败后回退到旧的下载逻辑

**最可能的原因**:
`SmbArchiveByteSource.readAt()` 使用 `File.inputStream` + `skip()` 实现有问题：
- 每次 `readAt` 调用都创建新的 InputStream
- `skip()` 对大偏移量可能不可靠
- smbj 的 InputStream 可能不支持正确的 skip 行为

**调试步骤**:
1. 在 `SpiderDen.tryOpenSmbStreamArchive` 开头添加日志确认是否被调用
2. 在 `SmbStreamArchiveReader.openArchive` 中捕获并打印异常
3. 检查 `ArchiveKt.openArchiveStream()` 的返回值
4. 使用 `adb logcat -s SpiderDen SmbStreamArchiveReader` 查看日志

**推荐修复方案**:
参考 LocalViewer 的 `SmbArchiveByteSource.kt`，改用 worker coroutine + channel 模式：
- 保持一个持久的 smbj File handle
- 使用 `File.read(offset, buffer, bufferOffset, length)` 进行随机读取
- 或者使用 smbj 的 `DiskShare.openFile()` 配合 `FILE_READ_DATA` 权限

**快速验证方法**:
在 `tryOpenSmbStreamArchive` 中临时禁用流式读取，确认旧逻辑仍然工作：
```java
// 临时禁用流式读取
InputStreamPipe streamPipe = null; // tryOpenSmbStreamArchive(index, resolved);
```

## 参考实现
LocalViewer 项目位于 `SMB/LocalViewer/`，包含完整的流式读取实现：
- `SmbGateway.kt` - 完整的 SMB 连接池（per-host 多会话 + 信号量复用）
- `SmbArchiveByteSource.kt` - worker coroutine + channel 的 SMB 读取
- `ArchiveStreamBridge.kt` - JNI 桥接
- `StreamArchivePageLoader.kt` - 页面加载器
- `archive.c` - 完整的 native 实现

## 编译命令
```bash
cd c:/Code/0_personal/MH-EH
./gradlew assembleDebug
```

## Git 提交
```
commit 6bf3a776
feat(smb): SMB CBZ 流式读取 - 只解析 ZIP CD + 按需解压
14 files changed, 3308 insertions(+), 113 deletions(-)
```
