package com.johnw.reelfin.ui

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign

/**
 * A small BlurHash decoder (written from the public spec at blurha.sh). Jellyfin sends a hash per
 * image tag, so tiles can show a blurred colour wash immediately while the real image loads.
 */
object BlurHash {
    private const val CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    private val cache = LruCache<String, Bitmap>(96)

    /** Decodes [hash] into a tiny bitmap (scale it up when drawing), or null if the hash is invalid. */
    fun bitmap(hash: String, width: Int = 32, height: Int = 18): Bitmap? {
        val key = "$hash/$width/$height"
        cache.get(key)?.let { return it }
        val pixels = decode(hash, width, height) ?: return null
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        cache.put(key, bmp)
        return bmp
    }

    /** Decodes to ARGB pixels, row by row. Pure Kotlin so it can be unit-tested. */
    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val numY = sizeFlag / 9 + 1
        val numX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null
        val quantMax = decode83(hash, 1, 2) ?: return null
        val maxValue = (quantMax + 1) / 166f * punch

        val colors = Array(numX * numY) { FloatArray(3) }
        val dc = decode83(hash, 2, 6) ?: return null
        colors[0][0] = srgbToLinear(dc shr 16)
        colors[0][1] = srgbToLinear((dc shr 8) and 255)
        colors[0][2] = srgbToLinear(dc and 255)
        for (i in 1 until numX * numY) {
            val v = decode83(hash, 4 + i * 2, 6 + i * 2) ?: return null
            colors[i][0] = signPow(((v / (19 * 19)) - 9) / 9f) * maxValue
            colors[i][1] = signPow((((v / 19) % 19) - 9) / 9f) * maxValue
            colors[i][2] = signPow(((v % 19) - 9) / 9f) * maxValue
        }

        val cosX = Array(numX) { i -> FloatArray(width) { x -> cos(PI * x * i / width).toFloat() } }
        val cosY = Array(numY) { j -> FloatArray(height) { y -> cos(PI * y * j / height).toFloat() } }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis = cosX[i][x] * cosY[j][y]
                        val c = colors[i + j * numX]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                out[y * width + x] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return out
    }

    private fun decode83(s: String, from: Int, to: Int): Int? {
        var value = 0
        for (k in from until to) {
            val d = CHARS.indexOf(s[k])
            if (d < 0) return null
            value = value * 83 + d
        }
        return value
    }

    private fun signPow(v: Float): Float = (abs(v).pow(2f)).withSign(v)

    private fun srgbToLinear(v: Int): Float {
        val f = v / 255f
        return if (f <= 0.04045f) f / 12.92f else ((f + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        return (s * 255f).roundToInt().coerceIn(0, 255)
    }
}
