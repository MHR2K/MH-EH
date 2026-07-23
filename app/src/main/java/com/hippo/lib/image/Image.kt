package com.hippo.lib.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.ALLOCATOR_DEFAULT
import android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
import android.graphics.ImageDecoder.DecodeException
import android.graphics.ImageDecoder.ImageInfo
import android.graphics.ImageDecoder.Source
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import com.hippo.ehviewer.EhApplication
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import com.hippo.ehviewer.Analytics


@Suppress("DEPRECATION")
class Image private constructor(
    source: InputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    val release: () -> Unit? = {},
) {
    private var mObtainedDrawable: Drawable?
    private var mBitmap: Bitmap? = null
    private var mReferences = 0

    init {
        mObtainedDrawable = null
        source?.let {
            var simpleSize: Int? = null
            if (source.available() > 10485760) {
                simpleSize = source.available() / 10485760 + 1
            }
            // FileInputStream 使用 FileChannel.map 做内存映射解码，性能更优
            // 其他 InputStream（如 SMB 流）读入 ByteArray 后解码，无需磁盘临时文件
            val isFile = source is FileInputStream
            val streamBytes: ByteArray? = if (isFile) null else {
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var len: Int
                while (source.read(chunk).also { len = it } != -1) {
                    buffer.write(chunk, 0, len)
                }
                buffer.toByteArray()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = if (isFile) {
                    ImageDecoder.createSource(
                        (source as FileInputStream).channel.map(
                            FileChannel.MapMode.READ_ONLY, 0,
                            source.available().toLong()
                        )
                    )
                } else {
                    ImageDecoder.createSource(streamBytes!!)
                }
                try {
                    mObtainedDrawable =
                        ImageDecoder.decodeDrawable(src) { decoder: ImageDecoder, info: ImageInfo, _: Source ->
                            decoder.allocator =
                                if (hardware) ALLOCATOR_DEFAULT else ALLOCATOR_SOFTWARE
                            // Sadly we must use software memory since we need copy it to tile buffer, fuck glgallery
                            // Idk it will cause how much performance regression

                            // 计算宽高比
                            val aspectRatio = info.size.height.toFloat() / info.size.width

                            // 基础采样大小：取宽高比的较小值
                            val baseSampleSize = min(
                                info.size.width / screenWidth,
                                info.size.height / screenHeight
                            ).coerceAtLeast(1)

                            // 根据屏幕密度动态调整采样
                            // 密度越高(sampleSize越小)，需要的像素越多
                            // densityDpi: mdpi=160, hdpi=240, xhdpi=320, xxhdpi=480, xxxhdpi=640
                            val densityScale = screenDensity / 160f

                            // 对于超长图，根据密度动态调整采样
                            // 高密度屏幕使用较低采样以保持清晰度
                            val finalSampleSize = when {
                                aspectRatio > 8 || aspectRatio < 0.25 -> {
                                    // 超长图：根据密度反向调整
                                    // 密度越高，采样越低（保留更多像素）
                                    val adjustedBase = (baseSampleSize / densityScale).coerceAtLeast(1f)
                                    max(adjustedBase.toInt(), simpleSize ?: 1)
                                }
                                else -> {
                                    // 普通图片：使用标准采样
                                    max(baseSampleSize, simpleSize ?: 1)
                                }
                            }

                            decoder.setTargetSampleSize(finalSampleSize)
                        }
                } catch (e: DecodeException) {
                    // ImageDecoder 失败时回退到 BitmapFactory
                    try {
                        // 根据流类型选择回退数据源
                        val fallbackStream = if (source is FileInputStream) {
                            val available = try { source.available() } catch (ex: Exception) { -1 }
                            if (available > 0) {
                                try { source.channel.position(0) } catch (ex: Exception) {
                                    Analytics.recordException(ex)
                                }
                            }
                            source as InputStream
                        } else {
                            java.io.ByteArrayInputStream(streamBytes!!)
                        }

                        if (simpleSize != null) {
                            val option = BitmapFactory.Options().apply {
                                inSampleSize = simpleSize
                            }
                            val bitmap = BitmapFactory.decodeStream(fallbackStream, null, option)
                            mObtainedDrawable =
                                bitmap?.toDrawable(EhApplication.getInstance().resources)
                        } else {
                            mObtainedDrawable = BitmapDrawable.createFromStream(fallbackStream, null)
                        }

                        if (mObtainedDrawable == null) {
                            throw Exception("BitmapFactory 解码返回 null")
                        }
                    } catch (fallbackException: Exception) {
                        fallbackException.initCause(e)
                        Analytics.recordException(fallbackException)
                        throw fallbackException
                    }
                }
                // Should we lazy decode it?
            } else {
                val decodeStream = if (isFile) source else java.io.ByteArrayInputStream(streamBytes!!)
                if (simpleSize != null) {
                    val option = BitmapFactory.Options().apply {
                        inSampleSize = simpleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(decodeStream, null, option)
                    mObtainedDrawable =
                        BitmapDrawable(EhApplication.getInstance().resources, bitmap)
                } else {
                    mObtainedDrawable = BitmapDrawable.createFromStream(decodeStream, null)
                }
            }
        }
        if (mObtainedDrawable == null) {
            mObtainedDrawable = drawable
//            throw IllegalArgumentException("数据解码出错")
        }
    }

    val animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        mObtainedDrawable is AnimatedImageDrawable
    } else {
        mObtainedDrawable is AnimationDrawable
    }
    val width =
        (mObtainedDrawable as? BitmapDrawable)?.bitmap?.width ?: mObtainedDrawable!!.intrinsicWidth
    val height = (mObtainedDrawable as? BitmapDrawable)?.bitmap?.height
        ?: mObtainedDrawable!!.intrinsicHeight
    val isRecycled = mObtainedDrawable == null

    private var started = false

    @Synchronized
    fun recycle() {
        if (mObtainedDrawable == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (mObtainedDrawable is AnimatedImageDrawable) {
                (mObtainedDrawable as AnimatedImageDrawable?)?.stop()
            }
        }
        if (mObtainedDrawable is BitmapDrawable) {
            (mObtainedDrawable as BitmapDrawable?)?.bitmap?.recycle()
        }
        mObtainedDrawable?.callback = null
        mObtainedDrawable = null
        mBitmap?.recycle()
        mBitmap = null
        release()
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = createBitmap(width, height)
    }

    private fun updateBitmap() {
        prepareBitmap()
        mObtainedDrawable!!.draw(Canvas(mBitmap!!))
    }

    @Synchronized
    fun obtain(): Boolean {
        return if (isRecycled) {
            false
        } else {
            ++mReferences
            true
        }
    }

    @Synchronized
    fun release() {
        --mReferences
        if (mReferences <= 0 && isRecycled) {
            recycle()
        }
    }

    fun getDrawable(): Drawable {
        check(obtain()) { "Recycled!" }
        return mObtainedDrawable as Drawable
    }

    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        try {
            val bitmap: Bitmap = if (animated) {
                updateBitmap()
                mBitmap!!
            } else {
                if (mObtainedDrawable == null) {
                    return
                }
                if (mObtainedDrawable is BitmapDrawable) {
                    (mObtainedDrawable as BitmapDrawable).bitmap
                } else {
                    val stickerBitmap = createBitmap(
                        mObtainedDrawable!!.intrinsicWidth,
                        mObtainedDrawable!!.intrinsicHeight
                    )
                    val canvas = Canvas(stickerBitmap)
                    mObtainedDrawable!!.setBounds(0, 0, stickerBitmap.width, stickerBitmap.height)
                    mObtainedDrawable!!.draw(canvas)
                    stickerBitmap
                }
            }
            nativeTexImage(
                bitmap,
                init,
                offsetX,
                offsetY,
                width,
                height
            )
        } catch (e: ClassCastException) {
            Analytics.recordException(e)
            return
        }
    }

    fun start() {
        if (!started) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (mObtainedDrawable as AnimatedImageDrawable?)?.start()
            }
        }
    }

    val delay: Int
        get() {
            if (animated)
                return 10
            return 0
        }

    @get:SuppressWarnings("deprecation")
    val isOpaque: Boolean
        get() {
            return mObtainedDrawable?.opacity == PixelFormat.OPAQUE
        }

    companion object {
        var screenWidth: Int = 0
        var screenHeight: Int = 0
        var screenDensity: Int = 160

        @JvmStatic
        fun initialize(ehApplication: EhApplication) {
            screenWidth = ehApplication.resources.displayMetrics.widthPixels
            screenHeight = ehApplication.resources.displayMetrics.heightPixels
            screenDensity = ehApplication.resources.displayMetrics.densityDpi
        }

        @JvmStatic
        fun decode(stream: FileInputStream, hardware: Boolean = true): Image? {
            try {
                return Image(stream, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

        @JvmStatic
        fun decode(inputStream: InputStream, hardware: Boolean = false): Image? {
            try {
                return Image(inputStream, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

        @JvmStatic
        fun decode(drawable: Drawable?, hardware: Boolean = true): Image? {
            try {
                return Image(null, drawable, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

//        @JvmStatic
//        fun decode(buffer: ByteBuffer, hardware: Boolean = true, release: () -> Unit? = {}): Image {
//            val src = ImageDecoder.createSource(buffer)
//            return Image(src, hardware = hardware) {
//                release()
//            }
//        }

        @JvmStatic
        fun create(bitmap: Bitmap): Image? {
            try {
                return Image(null, bitmap.toDrawable(Resources.getSystem()), false)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        @JvmStatic
        private external fun nativeRender(
            bitmap: Bitmap,
            srcX: Int, srcY: Int, dst: Bitmap, dstX: Int, dstY: Int,
            width: Int, height: Int,
        )

        @JvmStatic
        private external fun nativeTexImage(
            bitmap: Bitmap,
            init: Boolean,
            offsetX: Int,
            offsetY: Int,
            width: Int,
            height: Int,
        )
    }
}
