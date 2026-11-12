"""Session de vue d'un téléphone : signalisation + réception WebRTC (vidéo et son)."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Callable
from enum import Enum

import av
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError, MediaStreamTrack

from .discovery import PhoneInfo
from .pairings import Pairing
from .protocol import answer_msg, set_msg, start_msg, stop_msg
from .signaling import SignalingError, SignalingSession

log = logging.getLogger(__name__)


class ViewerState(Enum):
    CONNECTING = "connecting"
    LIVE = "live"
    LOST = "lost"
    ERROR = "error"
    STOPPED = "stopped"


MESSAGES = {
    "busy": "Ce téléphone est déjà regardé par un autre PC",
    "camera_busy": "La caméra du téléphone est utilisée par une autre application",
    "permission_missing": "Autorisez la caméra et le micro dans Eyes sur le téléphone",
    "bad_message": "Le téléphone n'a pas compris la demande",
    "media_error": "Le téléphone n'a pas pu démarrer la caméra ou le micro",
    "revoked": "Ce PC a été retiré sur le téléphone. Jumelez-le à nouveau.",
    "unknown": "Le téléphone ne reconnaît pas ce PC. Jumelez-le à nouveau.",
    "version": "Versions d'Eyes incompatibles : mettez à jour les deux applications",
    "cert_mismatch": "Ce téléphone ne correspond pas à celui jumelé : connexion refusée par sécurité",
    "unreachable": "Téléphone introuvable sur le Wi-Fi",
    "timeout": "Le téléphone ne répond pas",
}


def message_for(reason: str) -> str:
    return MESSAGES.get(reason, f"Erreur : {reason}")


class ViewerSession:
    def __init__(
        self,
        phone: PhoneInfo,
        pairing: Pairing,
        ident: tuple[str, str],
        on_frame: Callable[[av.VideoFrame], None],
        on_audio: Callable[[av.AudioFrame], None],
        on_state: Callable[[ViewerState, str | None], None],
    ) -> None:
        self.phone = phone
        self._pairing = pairing
        self._ident = ident
        self._on_frame = on_frame
        self._on_audio = on_audio
        self._on_state = on_state
        self._sig: SignalingSession | None = None
        self._pc: RTCPeerConnection | None = None
        self._tasks: set[asyncio.Task] = set()
        self._stopping = False
        self._live = False
        self.state: ViewerState | None = None

    def _set_state(self, state: ViewerState, message: str | None = None) -> None:
        if self.state in (ViewerState.ERROR, ViewerState.LOST, ViewerState.STOPPED) and state is not ViewerState.STOPPED:
            return  # état final déjà signalé
        self.state = state
        try:
            self._on_state(state, message)
        except Exception:
            log.exception("on_state a échoué")

    def _spawn(self, coro) -> asyncio.Task:
        t = asyncio.ensure_future(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)
        return t

    async def start(self, video: bool = True, audio: bool = True, camera: str = "back") -> None:
        """Se connecte et envoie la demande ; les erreurs passent par on_state, jamais en exception."""
        self._set_state(ViewerState.CONNECTING)
        try:
            sig = await SignalingSession.connect(self.phone, self._pairing, self._ident)
            if self._stopping:  # « Arrêter » pendant la connexion : ne rien démarrer
                await sig.close()
                return
            self._sig = sig
            await sig.send(start_msg(video, audio, camera))
        except SignalingError as exc:
            await self._fail(ViewerState.ERROR, message_for(exc.reason))
            return
        if self._stopping:
            with contextlib.suppress(Exception):
                await self._sig.send(stop_msg())
            await self._teardown()
            return
        self._spawn(self._run())

    async def set(self, **changes) -> None:
        if self._sig:
            with contextlib.suppress(SignalingError):
                await self._sig.send(set_msg(**changes))

    async def stop(self) -> None:
        self._stopping = True
        if self._sig:
            with contextlib.suppress(Exception):
                await self._sig.send(stop_msg())
        await self._teardown()
        self._set_state(ViewerState.STOPPED)

    async def _teardown(self) -> None:
        current = asyncio.current_task()
        for t in list(self._tasks):
            if t is not current:
                t.cancel()
        if self._pc:
            with contextlib.suppress(Exception):
                await self._pc.close()
            self._pc = None
        if self._sig:
            with contextlib.suppress(Exception):
                await self._sig.close()
            self._sig = None

    async def _fail(self, state: ViewerState, message: str | None) -> None:
        if self._stopping:
            return
        self._set_state(state, message)
        await self._teardown()

    async def _run(self) -> None:
        try:
            while True:
                msg = await self._sig.recv()
                kind = msg["type"]
                if kind == "offer":
                    await self._answer(msg["sdp"])
                elif kind in ("error", "denied"):
                    await self._fail(ViewerState.ERROR, message_for(msg.get("code") or msg.get("reason")))
                    return
        except SignalingError as exc:
            if exc.reason == "closed":
                await self._fail(ViewerState.LOST, "Connexion perdue")
            else:
                await self._fail(ViewerState.ERROR, message_for(exc.reason))
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("session de vue")
            await self._fail(ViewerState.ERROR, "Erreur inattendue")

    async def _answer(self, sdp: str) -> None:
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        self._pc = pc

        @pc.on("track")
        def on_track(track: MediaStreamTrack) -> None:
            self._spawn(self._read(track))

        @pc.on("connectionstatechange")
        async def on_conn_state() -> None:
            if pc.connectionState in ("failed", "closed") and pc is self._pc:
                await self._fail(ViewerState.LOST, "Connexion perdue")

        await pc.setRemoteDescription(RTCSessionDescription(sdp, "offer"))
        await pc.setLocalDescription(await pc.createAnswer())  # attend la fin de la collecte ICE
        await self._sig.send(answer_msg(pc.localDescription.sdp))

    async def _read(self, track: MediaStreamTrack) -> None:
        callback = self._on_frame if track.kind == "video" else self._on_audio
        while True:
            try:
                frame = await track.recv()
            except MediaStreamError:
                return
            if track.kind == "video" and not self._live:
                self._live = True
                self._set_state(ViewerState.LIVE)
            try:
                callback(frame)
            except Exception:
                log.exception("callback média")
