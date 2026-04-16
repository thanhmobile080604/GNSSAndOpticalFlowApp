package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES32
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer

object TextureLoader {
    fun loadTexture2D(context: Context, resourceId: Int): Int {
        val maxTextureSize = IntArray(1)
        GLES32.glGetIntegerv(GLES32.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        Log.d("Texture", "GL_MAX_TEXTURE_SIZE = ${maxTextureSize[0]}")

        val textureObjectIds = IntArray(1)
        GLES32.glGenTextures(1, textureObjectIds, 0)

        if (textureObjectIds[0] == 0) {
            Log.e("Texture", "Failed to generate texture ID")
            return 0
        }

        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = BitmapFactory.decodeResource(context.resources, resourceId, options)
        if (bitmap == null) {
            Log.e("Texture", "Decode bitmap failed")
            GLES32.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        Log.d("Texture", "Bitmap size = ${bitmap.width} x ${bitmap.height}")

        if (bitmap.width > maxTextureSize[0] || bitmap.height > maxTextureSize[0]) {
            Log.e(
                "Texture",
                "Bitmap exceeds GL_MAX_TEXTURE_SIZE: ${bitmap.width}x${bitmap.height}, max=${maxTextureSize[0]}"
            )
            bitmap.recycle()
            GLES32.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureObjectIds[0])

        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)

        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)

        val glErrorAfterUpload = GLES32.glGetError()
        if (glErrorAfterUpload != GLES32.GL_NO_ERROR) {
            Log.e("Texture", "gl error after texImage2D = $glErrorAfterUpload")
            bitmap.recycle()
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
            GLES32.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        bitmap.recycle()
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)

        return textureObjectIds[0]
    }

    fun loadCubeMap(faces: List<Int>, context: Context): Int {
        val textureIds = IntArray(1)
        GLES32.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]

        GLES32.glBindTexture(GLES32.GL_TEXTURE_CUBE_MAP, textureId)

        val options = BitmapFactory.Options().apply { inScaled = false }

        for (i in faces.indices) {
            val bitmap = BitmapFactory.decodeResource(context.resources, faces[i], options)
                ?: throw RuntimeException("Failed to load bitmap for cubemap face: $i")
            GLUtils.texImage2D(GLES32.GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, bitmap, 0)
            bitmap.recycle()
        }

        GLES32.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP, GLES32.GL_TEXTURE_WRAP_R, GLES32.GL_CLAMP_TO_EDGE)

        GLES32.glBindTexture(GLES32.GL_TEXTURE_CUBE_MAP, 0)

        return textureId
    }
}