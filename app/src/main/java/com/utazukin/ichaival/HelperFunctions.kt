/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Size
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.hippo.image.BitmapDecoder
import com.hippo.image.ImageInfo
import java.io.File
import java.util.regex.Pattern
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.math.max
import kotlin.math.min

fun getDpWidth(pxWidth: Int) : Int {
    val metrics = Resources.getSystem().displayMetrics
    return (pxWidth / metrics.density).toInt()
}

fun getDpAdjusted(pxSize: Int) : Int {
    val metrics = Resources.getSystem().displayMetrics
    return (pxSize * metrics.density).toInt()
}

fun getLastWord(query: String) : String {
    val regex = "\"([^\"]*)\"|(\\S+)"
    val matcher = Pattern.compile(regex).matcher(query)
    var last = ""
    while (matcher.find())
        matcher.group(2)?.let { last = it }

    return last
}

fun SharedPreferences?.castStringPrefToInt(pref: String, defaultValue: Int = 0) : Int {
    val stringPref = this?.getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toInt()
}

fun SharedPreferences?.castStringPrefToLong(pref: String, defaultValue: Long = 0) : Long {
    val stringPref = this?.getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toLong()
}

fun SharedPreferences?.castStringPrefToFloat(pref: String, defaultValue: Float = 0f) : Float {
    val stringPref = this?.getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toFloat()
}

fun Context.getCustomTheme() : String {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    return prefs.getString(getString(R.string.theme_pref), getString(R.string.dark_theme)).toString()
}

fun <T> MutableList<T>.removeRange(start: Int, count: Int) {
    var i = min(start + count, size - 1)
    while (i >= 0 && i >= start)
        removeAt(i--)
}

val BitmapFactory.Options.outSize: Size
    get() = Size(outWidth, outHeight)

fun getImageFormat(imageFile: File) : ImageFormat? {
    val info = ImageInfo()
    return imageFile.inputStream().use {
        if (BitmapDecoder.decode(it, info))
            ImageFormat.fromInt(info.format)
        else
            null
    }
}

fun downloadImageWithProgress(context: Context, imagePath: String, uiProgressListener: (Int) -> Unit) : FutureTarget<File> {
    return downloadImageWithProgress(context, imagePath, object: UIProgressListener {
        override fun update(progress: Int) {
            uiProgressListener(progress)
        }
    })
}

private fun downloadImageWithProgress(context: Context, imagePath: String, uiProgressListener: UIProgressListener) : FutureTarget<File> {
    ResponseProgressListener.expect(imagePath, uiProgressListener)
    return Glide.with(context)
        .downloadOnly()
        .load(imagePath)
        .listener(object: RequestListener<File> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<File>?,
                isFirstResource: Boolean
            ): Boolean {
                ResponseProgressListener.forget(imagePath)
                return false
            }

            override fun onResourceReady(
                resource: File?,
                model: Any?,
                target: Target<File>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                ResponseProgressListener.forget(imagePath)
                return false
            }

        })
        .submit()
}

fun Size.toRect() = Rect(0, 0, width, height)

inline fun <T> tryOrNull(body: () -> T) : T? {
    return try {
        body()
    }
    catch (e: Exception) {
        null
    }
}

private var mMaxTextureSize = -1
//Converted to Kotlin from
//https://stackoverflow.com/questions/7428996/hw-accelerated-activity-how-to-get-opengl-texture-size-limit/26823288#26823288
fun getMaxTextureSize() : Int {
    if (mMaxTextureSize > 0)
        return mMaxTextureSize

    val IMAGE_MAX_BITMAP_DIMENSION = 2048
    val egl: EGL10 = EGLContext.getEGL() as EGL10
    val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

    val version = IntArray(2)
    egl.eglInitialize(display, version)

    val totalConfigurations = IntArray(1)
    egl.eglGetConfigs(display, null, 0, totalConfigurations)

    val configurationsList = Array<EGLConfig?>(totalConfigurations[0]) { null }
    egl.eglGetConfigs(display, configurationsList, totalConfigurations[0], totalConfigurations)

    val textureSize = IntArray(1)
    var maxTextureSize = 0

    for (configuration in configurationsList) {
        egl.eglGetConfigAttrib(display, configuration, EGL10.EGL_MAX_PBUFFER_WIDTH, textureSize)

        if (maxTextureSize < textureSize[0])
            maxTextureSize = textureSize[0]
    }

    egl.eglTerminate(display)
    mMaxTextureSize = max(maxTextureSize, IMAGE_MAX_BITMAP_DIMENSION)
    return mMaxTextureSize
}