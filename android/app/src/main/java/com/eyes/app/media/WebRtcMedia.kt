package com.eyes.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.eyes.app.protocol.Camera
import com.eyes.app.protocol.SetMsg
import com.eyes.app.protocol.Start
import com.eyes.app.server.MediaController
import com.eyes.app.server.PermissionMissingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Caméra + micro du téléphone envoyés en WebRTC (offre `sendonly`, ICE hôte uniquement). */
class WebRtcMedia(private val context: Context) : MediaController {
    companion object {
        private const val TAG = "EyesMedia"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
        private const val CAMERA_START_TIMEOUT_MS = 4000L
        private const val ICE_GATHERING_TIMEOUT_MS = 3000L
        @Volatile private var initialized = false
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "eyes-media") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy { createFactory() }

    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var currentCamera = Camera.back
    private var iceComplete: CompletableDeferred<Unit>? = null

    /** Pour les tests : images capturées depuis le dernier start. */
    val framesCaptured = AtomicInteger(0)
    val videoEnabled: Boolean get() = videoTrack?.enabled() == true
    val audioEnabled: Boolean get() = audioTrack?.enabled() == true

    private fun createFactory(): PeerConnectionFactory {
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun hasPermissions(): Boolean = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun deviceFor(enumerator: Camera2Enumerator, camera: Camera): String? {
        val names = enumerator.deviceNames
        val wanted = names.firstOrNull { if (camera == Camera.front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
        return wanted ?: names.firstOrNull()
    }

    override suspend fun start(s: Start): String {
        if (!hasPermissions()) throw PermissionMissingException()
        withContext(dispatcher) { releaseLocked() }
        val watch = CameraStartWatch()
        val sdp = withContext(dispatcher) {
            framesCaptured.set(0)
            val enumerator = Camera2Enumerator(context)
            val device = deviceFor(enumerator, s.camera) ?: throw com.eyes.app.server.CameraBusyException()
            currentCamera = if (enumerator.isFrontFacing(device)) Camera.front else Camera.back
            val events = object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(description: String) { Log.w(TAG, "caméra : $description"); watch.onError(description) }
                override fun onCameraDisconnected() { watch.onDisconnected() }
                override fun onCameraFreezed(description: String) { Log.w(TAG, "caméra figée : $description") }
                override fun onCameraOpening(name: String) {}
                override fun onFirstFrameAvailable() { watch.onFirstFrame() }
                override fun onCameraClosed() {}
            }
            val cap = enumerator.createCapturer(device, events)
            val helper = SurfaceTextureHelper.create("eyes-capture", eglBase.eglBaseContext)
            val vSource = factory.createVideoSource(false)
            cap.initialize(helper, context, vSource.capturerObserver)
            cap.startCapture(WIDTH, HEIGHT, FPS)
            capturer = cap; surfaceHelper = helper; videoSource = vSource

            val vTrack = factory.createVideoTrack("video0", vSource).apply { setEnabled(s.video) }
            vTrack.addSink { framesCaptured.incrementAndGet() }
            val aSource = factory.createAudioSource(MediaConstraints())
            val aTrack = factory.createAudioTrack("audio0", aSource).apply { setEnabled(s.audio) }
            videoTrack = vTrack; audioTrack = aTrack; audioSource = aSource

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val ice = CompletableDeferred<Unit>()
            iceComplete = ice
            val peer = factory.createPeerConnection(config, Observer(ice)) ?: error("PeerConnection impossible")
            pc = peer
            val sendOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("eyes"))
            peer.addTransceiver(vTrack, sendOnly)
            peer.addTransceiver(aTrack, sendOnly)
            peer
        }.let { peer ->
            try {
                watch.await(CAMERA_START_TIMEOUT_MS)
            } catch (e: Exception) {
                withContext(dispatcher) { releaseLocked() }
                throw e
            }
            val offer = sdpCall<SessionDescription> { obs -> peer.createOffer(obs, MediaConstraints()) }
            sdpCall<Unit> { obs -> peer.setLocalDescription(obs, offer) }
            withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) { iceComplete?.await() }
            withContext(dispatcher) { peer.localDescription?.description ?: offer.description }
        }
        return sdp
    }

    override suspend fun applyAnswer(sdp: String) {
        val peer = withContext(dispatcher) { pc } ?: return
        sdpCall<Unit> { obs -> peer.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override fun set(m: SetMsg) {
        executor.submit {
            m.video?.let { videoTrack?.setEnabled(it) }
            m.audio?.let { audioTrack?.setEnabled(it) }
            m.camera?.let { switchTo(it) }
        }.get()
    }

    private fun switchTo(camera: Camera) {
        val cap = capturer ?: return
        if (camera == currentCamera) return
        val enumerator = Camera2Enumerator(context)
        val device = deviceFor(enumerator, camera) ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {}
            override fun onCameraSwitchError(error: String) { Log.w(TAG, "bascule caméra : $error") }
        }, device)
        currentCamera = camera
    }

    override fun stop() {
        executor.submit { releaseLocked() }.get()
    }

    private fun releaseLocked() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        pc?.dispose(); pc = null
        videoTrack = null; audioTrack = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        surfaceHelper?.dispose(); surfaceHelper = null
    }

    /** Termine une opération SDP asynchrone de WebRTC. */
    private suspend fun <T> sdpCall(block: (SdpObserver) -> Unit): T {
        val done = CompletableDeferred<T>()
        val obs = object : SdpObserver {
            @Suppress("UNCHECKED_CAST")
            override fun onCreateSuccess(desc: SessionDescription) { done.complete(desc as T) }
            @Suppress("UNCHECKED_CAST")
            override fun onSetSuccess() { done.complete(Unit as T) }
            override fun onCreateFailure(error: String) { done.completeExceptionally(IllegalStateException(error)) }
            override fun onSetFailure(error: String) { done.completeExceptionally(IllegalStateException(error)) }
        }
        withContext(dispatcher) { block(obs) }
        return done.await()
    }

    private class Observer(private val ice: CompletableDeferred<Unit>) : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) ice.complete(Unit)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { Log.i(TAG, "ICE : $state") }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }
}
