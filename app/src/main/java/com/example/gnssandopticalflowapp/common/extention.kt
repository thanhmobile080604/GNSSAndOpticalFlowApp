package com.example.gnssandopticalflowapp.common

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.random.Random

fun Context.getVersionName(): String? {
    return try {
        val pManager = packageManager
        val versionName =
            packageName?.let { pManager?.getPackageInfo(it, 0)?.versionName }
        val parts = versionName?.split(".")
        if (parts.isNullOrEmpty()) {
            null
        } else "v" + parts.joinToString("")
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun ImageView.loadAny(any: Any?) {
    when (any) {
        is Bitmap -> setImageBitmap(any)
        is Uri -> Glide.with(this).load(any).into(this)
        is String -> Glide.with(this).load(any).into(this)
        else -> this.setImageDrawable(null)
    }
}

suspend fun loadBitmapFromMediaUri(
    context: Context,
    uriString: String,
    maxDim: Int = 2048
): Bitmap? = withContext(Dispatchers.IO) {
    val uri = uriString.toUri()
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = if (w >= h) maxDim.toFloat() / w else maxDim.toFloat() / h
                if (scale < 1f) {
                    decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, maxDim, maxDim)
            val realOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, realOpts)
            } ?: return@withContext null

            val angle = context.contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it)
            }?.let { exif ->
                when (exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0

            if (angle != 0) {
                val m = Matrix().apply { postRotate(angle.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            val maxSide = maxOf(bmp.width, bmp.height)
            if (maxSide > maxDim) {
                val s = maxDim.toFloat() / maxSide
                bmp = bmp.scale((bmp.width * s).toInt(), (bmp.height * s).toInt())
            }
            bmp
        }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

suspend fun loadBitmapFromCache(
    ctx: Context,
    pathOrUri: String,
    maxDim: Int = 2048
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val uri = runCatching { Uri.parse(pathOrUri) }.getOrNull()

        when {
            // 1) Plain file path
            isPlainFilePath(pathOrUri) -> {
                if (!File(pathOrUri).exists()) {
                    return@withContext null
                }
                decodeFileScaled(pathOrUri, maxDim)?.let { bmp ->
                    val deg = exifRotationDegrees(pathOrUri)
                    if (deg != 0) bmp.rotate(deg.toFloat()) else bmp
                }
            }

            // 2) file://
            uri?.scheme.equals("file", ignoreCase = true) -> {
                val filePath = uri?.path ?: return@withContext null
                if (!File(filePath).exists()) {
                    return@withContext null
                }
                decodeFileScaled(filePath, maxDim)?.let { bmp ->
                    val deg = exifRotationDegrees(filePath)
                    if (deg != 0) bmp.rotate(deg.toFloat()) else bmp
                }
            }

            // 3) content://
            uri?.scheme.equals("content", ignoreCase = true) -> {
                // bounds
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri!!)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext null
                }
                val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bmp = ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null

                // EXIF từ stream (mở lại lần nữa)
                val deg = try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val exif = ExifInterface(input)
                        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90  -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } ?: 0
                } catch (_: Exception) { 0 }

                if (deg != 0) bmp = bmp.rotate(deg.toFloat())
                bmp
            }

            // 4) fallback: thử như file path
            else -> {
                if (File(pathOrUri).exists()) {
                    decodeFileScaled(pathOrUri, maxDim)?.let { bmp ->
                        val deg = exifRotationDegrees(pathOrUri)
                        if (deg != 0) bmp.rotate(deg.toFloat()) else bmp
                    }
                } else {
                    null
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

/* ===== Helpers ===== */

private fun isPlainFilePath(s: String): Boolean =
    s.startsWith("/") && !s.startsWith("/proc") && !s.startsWith("/dev")

private fun decodeFileScaled(path: String, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, opts)
}

private fun computeInSampleSize(w: Int, h: Int, maxDim: Int): Int {
    var inSampleSize = 1
    if (w > maxDim || h > maxDim) {
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / inSampleSize >= maxDim || halfH / inSampleSize >= maxDim) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

private fun exifRotationDegrees(path: String): Int = try {
    val exif = ExifInterface(path)
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (_: Exception) { 0 }

private fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val m = Matrix().apply { postRotate(degrees) }
    return try {
        Bitmap.createBitmap(this, 0, 0, width, height, m, true).also {
            if (it != this) this.recycle()
        }
    } catch (_: Throwable) {
        this // fallback
    }
}


fun View.applyEnabledTint(can: Boolean) {
    alpha = if (can) 1f else 0.4f
    isEnabled = can

    when (this) {
        is ImageView -> {
            if (can) {
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Color.WHITE))
                ImageViewCompat.setImageTintMode(this, PorterDuff.Mode.SRC_IN)
                // Nếu icon PNG cũ không ăn tintList, fallback:
                // setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            } else {
                ImageViewCompat.setImageTintList(this, null)
            }
        }
        is MaterialButton -> {
            iconTint = if (can) ColorStateList.valueOf(Color.WHITE) else null
            // nếu dùng text màu, có thể thêm:
            // setTextColor(if (can) Color.WHITE else defaultTextColor)
        }
        else -> { /* các view khác thì chỉ cần alpha/isEnabled là đủ */ }
    }
}

private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
    var inSampleSize = 1
    var halfW = w / 2
    var halfH = h / 2
    while (halfW / inSampleSize >= reqW && halfH / inSampleSize >= reqH) {
        inSampleSize *= 2
    }
    return inSampleSize.coerceAtLeast(1)
}


fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    val nw = connectivityManager?.activeNetwork ?: return false
    val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
    return when {
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        //for other device how are able to connect with Ethernet
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        //for check internet over Bluetooth
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
        else -> false
    }

}

fun Fragment.checkIfFragmentAttached(operation: Context.() -> Unit) {
    if (isAdded && context != null) {
        operation(requireContext())
    }
}

fun Fragment.safeContext(): Context {
    return if (isAdded && context != null) {
        requireContext()
    } else {
        throw IllegalStateException("Fragment not attached to context")
    }
}

// Extension function để sử dụng context một cách an toàn với fallback
fun Fragment.getContextSafely(): Context? {
    return if (isAdded && context != null) {
        context
    } else {
        null
    }
}

val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

fun getJsonFromAssets(context: Context, fileDirectory: String): String? {
    var jsonString: String? = null
    try {
        val inputStream = context.assets.open(fileDirectory)
        val size = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        jsonString = String(buffer, StandardCharsets.UTF_8)
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return jsonString
}

fun loadImageFromAsset(context: Context, directory: String, intoView: ImageView) {
    Glide.with(context).load(
        "file:///android_asset/$directory"
    ).into(intoView)
}


fun Context.getLinkFromRaw(name: String): String? {
    return try {
        val resourceId =
            resources.getIdentifier(name, "raw", packageName)
        Uri.parse(
            "android.resource://${packageName}/$resourceId"
        ).toString()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun cropBitmapOnPreview(bitmap: Bitmap, cropFrame: View, preview: View): Bitmap? {
    return try {
        // Lấy vị trí của khung crop trong cameraPreview
        val cropLocation = IntArray(2)
        cropFrame.getLocationInWindow(cropLocation) // Sử dụng tọa độ cửa sổ

        val previewLocation = IntArray(2)
        preview.getLocationInWindow(previewLocation)

        // Tính tọa độ khung crop tương đối với cameraPreview
        val relativeCropX = cropLocation[0] - previewLocation[0]
        val relativeCropY = cropLocation[1] - previewLocation[1]

        // Tính tỷ lệ giữa kích thước Bitmap và kích thước cameraPreview
        val widthScale = bitmap.width.toFloat() / preview.width
        val heightScale = bitmap.height.toFloat() / preview.height

        // Chuyển đổi tọa độ và kích thước crop sang tọa độ trong Bitmap
        val cropX = (relativeCropX * widthScale).toInt().coerceIn(0, bitmap.width)
        val cropY = (relativeCropY * heightScale).toInt().coerceIn(0, bitmap.height)
        val cropWidth = (cropFrame.width * widthScale).toInt().coerceAtMost(bitmap.width - cropX)
        val cropHeight =
            (cropFrame.height * heightScale).toInt().coerceAtMost(bitmap.height - cropY)

        // Crop ảnh
        Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun updateCropFrameRandomly(
    zigzagCount: Int,
    cropFrame: View,
    preview: View,
    onComplete: () -> Unit
) {
    try {
        val parentWidth = preview.width
        val parentHeight = preview.height
        if (parentWidth == 0 || parentHeight == 0) return
        val layoutParams = cropFrame.layoutParams as ConstraintLayout.LayoutParams
        val currentX = layoutParams.leftMargin
        val currentY = layoutParams.topMargin
        val currentWidth = layoutParams.width
        val currentHeight = layoutParams.height

        val newWidth = Random.nextInt(
            (parentWidth / 2 - parentWidth / 10),
            (parentWidth / 2 + parentWidth / 6)
        )
        val newHeight = Random.nextInt(parentHeight / 5, (parentHeight / 2 - parentHeight / 10))

        val maxDistance = (parentWidth / 2).toInt()
        val directions = listOf("UP", "DOWN", "LEFT", "RIGHT")
        var previousDirection: String? = null

        val points = mutableListOf<Pair<Int, Int>>()
        var lastX = currentX
        var lastY = currentY

        for (i in 1..zigzagCount) {
            // Kiểm tra vùng an toàn
            val safeZone = 50  // Khoảng cách tối thiểu từ biên để được coi là an toàn
            val atTop = lastY <= safeZone
            val atBottom = lastY + currentHeight >= parentHeight - safeZone
            val atLeft = lastX <= safeZone
            val atRight = lastX + currentWidth >= parentWidth - safeZone

            // Loại bỏ hướng có nguy cơ chạm giới hạn
            val allowedDirections = directions.filter { direction ->
                when (direction) {
                    "UP" -> !atTop && previousDirection != "DOWN"
                    "DOWN" -> !atBottom && previousDirection != "UP"
                    "LEFT" -> !atLeft && previousDirection != "RIGHT"
                    "RIGHT" -> !atRight && previousDirection != "LEFT"
                    else -> true
                }
            }

            // Nếu không còn hướng hợp lệ, thoát khỏi vòng lặp
            if (allowedDirections.isEmpty()) break

            // Chọn hướng ngẫu nhiên, ưu tiên hướng xa biên giới
            val newDirection = allowedDirections.random()
            previousDirection = newDirection

            // Tính toán vị trí tiếp theo
            when (newDirection) {
                "UP" -> lastY = maxOf(0, lastY - Random.nextInt(100, maxDistance))
                "DOWN" -> lastY =
                    minOf(parentHeight - newHeight, lastY + Random.nextInt(100, maxDistance))

                "LEFT" -> lastX = maxOf(0, lastX - Random.nextInt(100, maxDistance))
                "RIGHT" -> lastX =
                    minOf(parentWidth - newWidth, lastX + Random.nextInt(100, maxDistance))
            }

            points.add(lastX to lastY)
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210L * (zigzagCount + 1)
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                try {
                    val progress = animation.animatedValue as Float
                    val segmentProgress = progress * (zigzagCount + 1) % 1f
                    val currentSegment = (progress * (zigzagCount + 1)).toInt()

                    val (startX, startY) = when {
                        currentSegment == 0 -> currentX to currentY
                        currentSegment <= zigzagCount -> points[currentSegment - 1]
                        else -> points.last()
                    }

                    val (endX, endY) = when {
                        currentSegment < zigzagCount -> points[currentSegment]
                        else -> points.last()
                    }

                    val interpolatedX = leap(startX, endX, segmentProgress)
                    val interpolatedY = leap(startY, endY, segmentProgress)
                    val interpolatedWidth = leap(currentWidth, newWidth, progress)
                    val interpolatedHeight = leap(currentHeight, newHeight, progress)

                    layoutParams.leftMargin = interpolatedX.toInt()
                    layoutParams.topMargin = interpolatedY.toInt()
                    layoutParams.width = interpolatedWidth.toInt()
                    layoutParams.height = interpolatedHeight.toInt()
                    cropFrame.layoutParams = layoutParams
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        animator.start()
        animator.doOnEnd {
            layoutParams.leftMargin = points.last().first
            layoutParams.topMargin = points.last().second
            layoutParams.width = newWidth
            layoutParams.height = newHeight
            cropFrame.layoutParams = layoutParams
            onComplete.invoke()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        onComplete.invoke()
    }
}

private fun leap(start: Int, end: Int, progress: Float): Float {
    return start + (end - start) * progress
}

fun loadBitmapFromAssets(context: Context, fileName: String): Bitmap? {
    return try {
        val inputStream = context.assets.open(fileName)
        BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun ImageView.loadImageFromAsset(path: String) {
    Glide.with(this.context)
        .load("file:///android_asset/${path}".toUri())
        .into(this)
}

fun View.setSingleClick(
    clickSpendTime: Long = 500L,
    execution: () -> Unit
) {
    setOnClickListener(object : View.OnClickListener {
        var lastClickTime: Long = 0
        override fun onClick(p0: View?) {
            if (SystemClock.elapsedRealtime() - lastClickTime < clickSpendTime) {
                return
            }
            lastClickTime = SystemClock.elapsedRealtime()
            execution.invoke()
        }
    })
}

fun View?.show() {
    this?.visibility = View.VISIBLE
}

fun View?.hide() {
    this?.visibility = View.GONE
}



