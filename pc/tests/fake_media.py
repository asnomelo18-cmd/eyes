"""Côté « téléphone » WebRTC factice pour les tests : envoie des pistes de test via aiortc."""

from __future__ import annotations

import asyncio

from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack, VideoStreamTrack


class FakeMedia:
    """Branché sur FakePhone.media_handler : répond à start/set/stop comme le vrai téléphone."""

    def __init__(self, error_code: str | None = None) -> None:
        self.pc: RTCPeerConnection | None = None
        self.error_code = error_code
        self.sets: list[dict] = []
        self.stopped = asyncio.Event()

    async def __call__(self, msg: dict, send) -> None:
        kind = msg["type"]
        if kind == "start":
            if self.error_code:
                await send({"type": "error", "code": self.error_code})
                return
            self.pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
            self.pc.addTrack(VideoStreamTrack())
            self.pc.addTrack(AudioStreamTrack())
            await self.pc.setLocalDescription(await self.pc.createOffer())
            await send({"type": "offer", "sdp": self.pc.localDescription.sdp})
        elif kind == "answer":
            await self.pc.setRemoteDescription(RTCSessionDescription(msg["sdp"], "answer"))
        elif kind == "set":
            self.sets.append(msg)
        elif kind == "stop":
            await self.close()
            self.stopped.set()

    async def on_disconnect(self) -> None:
        await self.close()
        self.stopped.set()

    async def close(self) -> None:
        if self.pc:
            await self.pc.close()
