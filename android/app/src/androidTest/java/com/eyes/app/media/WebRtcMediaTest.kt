package com.eyes.app.media

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.eyes.app.protocol.Camera
import com.eyes.app.protocol.SetMsg
import com.eyes.app.protocol.Start
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebRtcMediaTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = WebRtcMedia(context)

    @After fun tearDown() { media.stop() }

    private fun waitFrames(min: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (media.framesCaptured.get() < min && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return media.framesCaptured.get() >= min
    }

    @Test
    fun startProducesOfferWithVideoAndAudio() = runBlocking {
        val sdp = media.start(Start(video = true, audio = true, camera = Camera.back))
        assertTrue("m=video", sdp.contains("m=video"))
        assertTrue("m=audio", sdp.contains("m=audio"))
        assertTrue("candidat hôte", sdp.contains("typ host"))
        assertTrue("sendonly", sdp.contains("a=sendonly"))
        assertTrue("images capturées", waitFrames(5))
    }

    @Test
    fun switchCameraDoesNotRenegotiate() = runBlocking {
        media.start(Start(true, true, Camera.back))
        assertTrue(waitFrames(3))
        media.set(SetMsg(camera = Camera.front))
        val before = media.framesCaptured.get()
        Thread.sleep(2000)
        assertTrue("les images continuent après la bascule", media.framesCaptured.get() > before + 5)
    }

    @Test
    fun setVideoFalseDisablesTrack() = runBlocking {
        media.start(Start(true, true, Camera.back))
        media.set(SetMsg(video = false, audio = false))
        assertFalse(media.videoEnabled)
        assertFalse(media.audioEnabled)
        media.set(SetMsg(video = true))
        assertTrue(media.videoEnabled)
    }

    @Test
    fun stopReleasesCamera() = runBlocking {
        media.start(Start(true, true, Camera.back))
        assertTrue(waitFrames(3))
        media.stop()
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first()
        val thread = HandlerThread("test-cam").apply { start() }
        val opened = CompletableFuture<Boolean>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { opened.complete(true); camera.close() }
            override fun onDisconnected(camera: CameraDevice) { opened.complete(false); camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { opened.complete(false); camera.close() }
        }, Handler(thread.looper))
        assertTrue("la caméra est libérée", opened.get(5, TimeUnit.SECONDS))
        thread.quitSafely()
    }

    @Test
    fun disposeReleasesCameraAndCanBeCalledAfterStart() = runBlocking {
        val m = WebRtcMedia(context)
        m.start(Start(true, true, Camera.back))
        m.dispose()
        // une nouvelle instance peut reprendre la caméra
        val again = WebRtcMedia(context)
        try {
            again.start(Start(true, true, Camera.back))
        } finally {
            again.dispose()
        }
    }
}
