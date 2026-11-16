package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 1.4.0-beta2: the one place a picture is encoded. Size and JPEG quality for every use are in [Use]; no other file
 * calls Bitmap.compress (a test keeps it that way). Before this, five places each picked their own numbers, so how
 * clear a picture was depended on which command made it, and a picture sent to a model was decoded and encoded a
 * second time.
 */
object Pictures {
    /** [side]: longest side in pixels (0 = the caller already sized it). */
    enum class Use(val side: Int, val quality: Int) {
        /** look --detail low | normal | high (and act's own picture, which is normal). */
        LOOK_LOW(768, 60), LOOK(1280, 72), LOOK_HIGH(2400, 85),
        /** The copy of a picture that goes into a model context by itself (no one asked for it): see ImagePolicy. */
        SENT(1024, 60),
        /** A picture shown to the user with a question. */
        ASK(1600, 85),
        /** A lesson's contact sheet, kept with the lesson. */
        LESSON(0, 75),
        /** A frame of the recording track (many, kept minutes). */
        RECORDING(0, 60),
    }

    /** [bmp] no longer than [side] (the same bitmap when it already fits). */
    fun fit(bmp: Bitmap, side: Int): Bitmap {
        val long = maxOf(bmp.width, bmp.height)
        if (side <= 0 || long <= side) return bmp
        return Bitmap.createScaledBitmap(bmp, maxOf(1, bmp.width * side / long), maxOf(1, bmp.height * side / long), true)
    }

    /** Write [bmp] as JPEG for [use] (sized to use.side unless 0). [quality] overrides only for a caller that has its own table entry. */
    fun write(bmp: Bitmap, f: File, use: Use, quality: Int = use.quality, fit: Boolean = true): File {
        val b = if (fit) fit(bmp, use.side) else bmp
        try { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) } } finally { if (b !== bmp) b.recycle() }
        return f
    }

    /**
     * What a picture shows, coarsely: 16×16 cells of mean brightness, 8 levels each. Equal for two pictures of the
     * same page (a ticking clock or a caret does not move a cell's mean across a level), different when the page
     * changed. Used to say "same as the last picture you got" only when it is true.
     */
    fun sig(bmp: Bitmap): Long {
        val n = 16
        val s = Bitmap.createScaledBitmap(bmp, n, n, true)
        var h = 1469598103934665603L
        for (y in 0 until n) for (x in 0 until n) {
            val c = s.getPixel(x, y)
            val l = ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10
            h = (h xor (l shr 5).toLong()) * 1099511628211L
        }
        if (s !== bmp) s.recycle()
        return h
    }
}
