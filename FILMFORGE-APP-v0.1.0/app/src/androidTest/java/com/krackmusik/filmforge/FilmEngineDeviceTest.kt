package com.krackmusik.filmforge

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.common.util.UnstableApi
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FilmEngineDeviceTest {
    @Test fun recipesCompileAndRenderOnAndroidGpu() {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        val count=IntArray(1)
        val attrs=intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,
            EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],
            intArrayOf(EGL14.EGL_WIDTH,64,EGL14.EGL_HEIGHT,64,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context))
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val state=FilmState()
        val neural=NeuralDetail(app)
        val shader=LiveFilmEffect(state,neural) { error(it) }.toGlShaderProgram(app,false) as BaseGlShaderProgram
        val texture=IntArray(1)
        try {
            shader.configure(64,48)
            GLES20.glGenTextures(1,texture,0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            val data=ByteBuffer.allocateDirect(64*48*4)
            repeat(64*48){data.put(80);data.put(50);data.put(30);data.put(255.toByte())}
            data.flip()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,64,48,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,data)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
            GLES20.glViewport(0,0,64,48)
            var time=1_000_000L
            for(recipe in FilmRecipe.presets()+FilmRecipe("Motion test",motion=.5f)) {
                state.recipe=recipe
                shader.drawFrame(texture[0],time)
                time+=33_333L
                val out=ByteBuffer.allocateDirect(4)
                GLES20.glReadPixels(32,24,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,out)
                assertEquals("GL error in "+recipe.name,GLES20.GL_NO_ERROR,GLES20.glGetError())
                assertEquals(255,out.get(3).toInt() and 255)
                if(recipe.name=="Original"){
                    assertEquals(80.0,(out.get(0).toInt() and 255).toDouble(),2.0)
                    assertEquals(50.0,(out.get(1).toInt() and 255).toDouble(),2.0)
                }
                if(recipe.name=="HP5"){
                    assertEquals(out.get(0),out.get(1));assertEquals(out.get(1),out.get(2))
                }
            }
        }finally{
            shader.release();neural.close()
            GLES20.glDeleteTextures(1,texture,0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display)
        }
    }

    @Test fun trainedModelRunsWithAndroidRuntime() {
        val neural=NeuralDetail(InstrumentationRegistry.getInstrumentation().targetContext)
        try{
            neural.prepare() // The speed gate may reject an emulator; inference must still work.
            val out=neural.infer(FloatArray(128*96){.5f},128,96)
            assertEquals(128*96*9,out.size)
            assertTrue(out.all{it.isFinite()})
            assertTrue(out.any{it>.1f})
        }finally{neural.close()}
    }

    @Test fun customRecipesSurviveJsonRoundTrip() {
        val recipe=FilmRecipe("My saved look",contrast=.4f,grain=.3f,halation=.5f,skin=.8f)
        assertEquals(recipe,FilmRecipe.fromJson(org.json.JSONObject(recipe.toJson().toString())))
    }
}
