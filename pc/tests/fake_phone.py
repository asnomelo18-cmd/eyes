"""Faux téléphone Eyes pour les tests : serveur wss avec certificat auto-signé."""

from __future__ import annotations

import asyncio
import datetime
import hashlib
import secrets
import ssl
import tempfile
from collections.abc import Awaitable, Callable
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from websockets.asyncio.server import serve

from eyes_pc.discovery import PhoneInfo
from eyes_pc.protocol import ProtocolError, decode, encode


def make_cert(dirpath: Path) -> tuple[Path, Path, str]:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "eyes-test")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .sign(key, hashes.SHA256())
    )
    tag = secrets.token_hex(4)
    cert_path, key_path = dirpath / f"cert{tag}.pem", dirpath / f"key{tag}.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    return cert_path, key_path, fp


# Gestionnaire de session média branché par les tests de la Tâche 5 :
# reçoit (message, send) pour chaque message après `welcome`.
MediaHandler = Callable[[dict, Callable[[dict], Awaitable[None]]], Awaitable[None]]


class FakePhone:
    def __init__(self) -> None:
        self._dir = Path(tempfile.mkdtemp())
        self.phone_id = "ph-test"
        self.name = "Téléphone test"
        self.code = "482913"
        self.issued_token: str | None = None
        self.tokens: dict[str, str] = {}
        self.revoked: set[str] = set()
        self.received: list[dict] = []
        self.media_handler: MediaHandler | None = None
        self.connections: list = []
        self._server = None
        self.port = 0
        self._new_cert()

    def _new_cert(self) -> None:
        cert, key, self.cert_sha256 = make_cert(self._dir)
        self._ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._ctx.load_cert_chain(cert, key)

    @property
    def info(self) -> PhoneInfo:
        return PhoneInfo(self.phone_id, self.name, "127.0.0.1", self.port, False, "mdns")

    def revoke(self, pc_id: str) -> None:
        self.tokens.pop(pc_id, None)
        self.revoked.add(pc_id)

    async def change_cert(self) -> None:
        await self.stop()
        self._new_cert()
        await self.start(self.port)

    async def drop_all(self) -> None:
        for ws in list(self.connections):
            ws.transport.abort()

    async def start(self, port: int = 0) -> None:
        self._server = await serve(self._handle, "127.0.0.1", port, ssl=self._ctx)
        self.port = self._server.sockets[0].getsockname()[1]

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    async def _handle(self, ws) -> None:
        self.connections.append(ws)

        async def send(msg: dict) -> None:
            await ws.send(encode(msg))

        authed = False
        try:
            async for raw in ws:
                try:
                    msg = decode(raw)
                except ProtocolError:
                    await send({"type": "error", "code": "bad_message"})
                    continue
                self.received.append(msg)
                if msg["type"] == "pair":
                    if msg["code"] != self.code:
                        await send({"type": "denied", "reason": "bad_code"})
                        return
                    self.issued_token = secrets.token_urlsafe(32)
                    self.tokens[msg["pcId"]] = self.issued_token
                    await send({"type": "paired", "token": self.issued_token,
                                "phoneId": self.phone_id, "phoneName": self.name})
                    return
                if msg["type"] == "hello":
                    if msg["pcId"] in self.revoked:
                        await send({"type": "denied", "reason": "revoked"})
                        return
                    if self.tokens.get(msg["pcId"]) != msg["token"]:
                        await send({"type": "denied", "reason": "unknown"})
                        return
                    authed = True
                    await send({"type": "welcome", "phoneId": self.phone_id, "phoneName": self.name})
                    continue
                if not authed:
                    await send({"type": "denied", "reason": "unknown"})
                    return
                if self.media_handler:
                    await self.media_handler(msg, send)
        except Exception:  # comme le vrai téléphone : une erreur d'envoi termine la session
            pass
        finally:
            self.connections.remove(ws)
            # comme le vrai téléphone : une déconnexion libère caméra et micro
            if authed and self.media_handler and hasattr(self.media_handler, "on_disconnect"):
                await self.media_handler.on_disconnect()
