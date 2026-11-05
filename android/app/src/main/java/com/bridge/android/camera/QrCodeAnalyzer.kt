package com.bridge.android.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom CameraX ImageAnalysis.Analyzer using pure ZXing core library.
 * Completely offline, zero dependency on Google Play Services or Google Camera.
 */
class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.CHARACTER_SET, "UTF-8")
        put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
    }

    private val isEnabled = AtomicBoolean(true)

    fun pause() {
        isEnabled.set(false)
    }

    fun resume() {
        isEnabled.set(true)
    }

    override fun analyze(image: ImageProxy) {
        if (!isEnabled.get()) {
            image.close()
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val yBytes = ByteArray(width * height)
            if (rowStride == width && pixelStride == 1) {
                buffer.get(yBytes, 0, width * height)
            } else {
                for (row in 0 until height) {
                    val rowStart = row * rowStride
                    for (col in 0 until width) {
                        yBytes[row * width + col] = buffer.get(rowStart + col * pixelStride)
                    }
                }
            }

            val rotationDegrees = image.imageInfo.rotationDegrees
            val (rotatedBytes, rotWidth, rotHeight) = when (rotationDegrees) {
                90 -> Triple(rotate90(yBytes, width, height), height, width)
                180 -> Triple(rotate180(yBytes, width, height), width, height)
                270 -> Triple(rotate270(yBytes, width, height), height, width)
                else -> Triple(yBytes, width, height)
            }

            val source = PlanarYUVLuminanceSource(
                rotatedBytes,
                rotWidth,
                rotHeight,
                0, 0,
                rotWidth,
                rotHeight,
                false
            )

            var scannedText: String? = null
            // Primary pass: HybridBinarizer
            try {
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decode(bitmap, hints)
                scannedText = result.text
            } catch (_: NotFoundException) {
                // Secondary fallback: GlobalHistogramBinarizer for low-contrast frames
                try {
                    val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                    val result = reader.decode(bitmap, hints)
                    scannedText = result.text
                } catch (_: Exception) {
                    // Not found in this frame
                }
            } catch (_: Exception) {
                // Ignore parse errors on partial frames
            } finally {
                reader.reset()
            }

            if (!scannedText.isNullOrEmpty()) {
                if (isEnabled.compareAndSet(true, false)) {
                    onQrCodeScanned(scannedText)
                }
            }
        } catch (_: Exception) {
            // Protect against unexpected buffer issues
        } finally {
            image.close()
        }
    }

    private fun rotate90(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                rotated[x * height + (height - y - 1)] = data[y * width + x]
            }
        }
        return rotated
    }

    private fun rotate180(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        val size = width * height
        for (i in 0 until size) {
            rotated[size - 1 - i] = data[i]
        }
        return rotated
    }

    private fun rotate270(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                rotated[(width - 1 - x) * height + y] = data[y * width + x]
            }
        }
        return rotated
    }
}
