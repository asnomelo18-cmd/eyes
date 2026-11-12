package com.eyes.app.media

import com.eyes.app.server.CameraBusyException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraStartWatchTest {
    @Test
    fun firstFrameMeansStarted() = runTest {
        val w = CameraStartWatch()
        w.onFirstFrame()
        w.await(1000)
    }

    @Test
    fun cameraErrorMeansBusy() = runTest {
        val w = CameraStartWatch()
        w.onError("Camera in use")
        assertThrows(CameraBusyException::class.java) { kotlinx.coroutines.runBlocking { w.await(1000) } }
    }

    @Test
    fun disconnectBeforeFirstFrameMeansBusy() = runTest {
        val w = CameraStartWatch()
        w.onDisconnected()
        assertThrows(CameraBusyException::class.java) { kotlinx.coroutines.runBlocking { w.await(1000) } }
    }

    @Test
    fun noFrameInTimeMeansBusy() {
        val w = CameraStartWatch()
        assertThrows(CameraBusyException::class.java) { kotlinx.coroutines.runBlocking { w.await(50) } }
    }

    @Test
    fun errorAfterFirstFrameIsIgnoredByAwait() = runTest {
        val w = CameraStartWatch()
        w.onFirstFrame()
        w.onError("plus tard")
        w.await(1000)
    }
}
