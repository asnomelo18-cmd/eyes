"""Connexion sécurisée (wss) au téléphone : jumelage, authentification, échange de messages."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import ssl

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed, InvalidHandshake

from .discovery import PhoneInfo
from .pairings import Pairing, PairingStore
from .protocol import ProtocolError, decode, encode, hello_msg, pair_msg

CONNECT_TIMEOUT_S = 5.0
# Un téléphone écran éteint répond lentement et perd parfois une tentative : on réessaie.
CONNECT_ATTEMPTS = 3
RETRY_DELAY_S = 1.0
REPLY_TIMEOUT_S = 10.0


class SignalingError(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _ssl_context() -> ssl.SSLContext:
    # Certificat auto-signé : pas d'autorité, on vérifie l'empreinte épinglée à la main.
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


async def _open(phone: PhoneInfo, expected_sha256: str | None) -> tuple[ClientConnection, str]:
    for attempt in range(1, CONNECT_ATTEMPTS + 1):
        try:
            ws = await asyncio.wait_for(
                connect(f"wss://{phone.host}:{phone.port}/", ssl=_ssl_context(), open_timeout=None,
                        max_size=2**20),
                CONNECT_TIMEOUT_S,
            )
            break
        except (OSError, asyncio.TimeoutError, InvalidHandshake, ssl.SSLError) as exc:
            if attempt == CONNECT_ATTEMPTS:
                raise SignalingError("unreachable") from exc
            await asyncio.sleep(RETRY_DELAY_S)
    der = ws.transport.get_extra_info("ssl_object").getpeercert(binary_form=True)
    fingerprint = hashlib.sha256(der).hexdigest()
    if expected_sha256 is not None and not hmac.compare_digest(fingerprint, expected_sha256):
        await ws.close()
        raise SignalingError("cert_mismatch")
    return ws, fingerprint


async def _recv(ws: ClientConnection, timeout: float | None) -> dict:
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout)
    except ConnectionClosed as exc:
        raise SignalingError("closed") from exc
    except asyncio.TimeoutError as exc:
        raise SignalingError("timeout") from exc
    try:
        return decode(raw)
    except ProtocolError as exc:
        raise SignalingError("bad_message") from exc


async def pair(phone: PhoneInfo, code: str, store: PairingStore, ident: tuple[str, str]) -> Pairing:
    pc_id, pc_name = ident
    ws, fingerprint = await _open(phone, None)
    try:
        await ws.send(encode(pair_msg(code, pc_id, pc_name)))
        reply = await _recv(ws, REPLY_TIMEOUT_S)
    finally:
        await ws.close()
    if reply["type"] == "denied":
        raise SignalingError(reply["reason"])
    if reply["type"] != "paired":
        raise SignalingError("bad_message")
    pairing = Pairing(reply["phoneId"], reply["phoneName"], reply["token"], fingerprint,
                      phone.host, phone.port)
    store.save(pairing)
    return pairing


class SignalingSession:
    def __init__(self, ws: ClientConnection, phone_name: str) -> None:
        self._ws = ws
        self.phone_name = phone_name

    @classmethod
    async def connect(cls, phone: PhoneInfo, pairing: Pairing, ident: tuple[str, str]) -> "SignalingSession":
        ws, _ = await _open(phone, pairing.cert_sha256)
        try:
            await ws.send(encode(hello_msg(ident[0], pairing.token)))
            reply = await _recv(ws, REPLY_TIMEOUT_S)
        except BaseException:
            await ws.close()
            raise
        if reply["type"] != "welcome":
            await ws.close()
            raise SignalingError(reply.get("reason") or reply.get("code") or "bad_message")
        return cls(ws, reply["phoneName"])

    async def send(self, msg: dict) -> None:
        try:
            await self._ws.send(encode(msg))
        except ConnectionClosed as exc:
            raise SignalingError("closed") from exc

    async def recv(self, timeout: float | None = None) -> dict:
        return await _recv(self._ws, timeout)

    async def close(self) -> None:
        await self._ws.close()
