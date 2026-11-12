"""Connexions maintenues ouvertes avec les téléphones jumelés.

Certains téléphones (vérifié sur Huawei) refusent toute nouvelle connexion entrante quand ils sont
sur batterie, écran éteint, mais gardent celles déjà ouvertes. Le PC garde donc une connexion
authentifiée par téléphone jumelé (vérifiée par un ping toutes les 15 s) et « Voir » l'emprunte
au lieu d'en ouvrir une nouvelle. Si elle tombe, on la rouvre dès que le téléphone répond.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import logging
from collections.abc import Callable, Iterable

from .discovery import PhoneInfo
from .pairings import Pairing, PairingStore
from .signaling import SignalingError, SignalingSession

log = logging.getLogger(__name__)

RETRY_S = 5.0
REFUSALS = {"revoked", "unknown", "version", "cert_mismatch"}  # inutile d'insister


def with_links(phones: Iterable[PhoneInfo], linked: set[str]) -> list[PhoneInfo]:
    """Un téléphone jumelé invisible sur le Wi-Fi reste joignable s'il a une connexion maintenue."""
    return [dataclasses.replace(p, via="link") if p.via == "none" and p.phone_id in linked else p
            for p in phones]


class LinkPool:
    def __init__(self, store: PairingStore, ident: tuple[str, str],
                 on_change: Callable[[], None] | None = None, retry_s: float = RETRY_S) -> None:
        self._store = store
        self._ident = ident
        self._on_change = on_change or (lambda: None)
        self._retry_s = retry_s
        self._idle: dict[str, tuple[SignalingSession, asyncio.Task]] = {}
        self._lent: dict[str, SignalingSession] = {}
        self._connecting: set[str] = set()
        self._refused: dict[str, str] = {}  # phone_id -> jeton refusé (on réessaie après un nouveau jumelage)
        self._tasks: set[asyncio.Task] = set()
        self._loop_task: asyncio.Task | None = None

    # --- état
    def linked(self) -> set[str]:
        return set(self._idle) | set(self._lent)

    def _notify(self) -> None:
        try:
            self._on_change()
        except Exception:
            log.exception("on_change a échoué")

    def _spawn(self, coro) -> asyncio.Task:
        t = asyncio.ensure_future(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)
        return t

    # --- cycle de vie
    async def start(self) -> None:
        self._loop_task = self._spawn(self._loop())

    async def stop(self) -> None:
        for t in list(self._tasks):
            t.cancel()
        for t in list(self._tasks):
            with contextlib.suppress(BaseException):
                await t
        for sig, _ in list(self._idle.values()):
            with contextlib.suppress(Exception):
                await sig.close()
        self._idle.clear()

    async def _loop(self) -> None:
        while True:
            for pairing in self._store.load().values():
                pid = pairing.phone_id
                if pid in self._idle or pid in self._lent or pid in self._connecting:
                    continue
                if self._refused.get(pid) == pairing.token or not pairing.last_host:
                    continue
                self._connecting.add(pid)
                self._spawn(self._open(pairing))
            await asyncio.sleep(self._retry_s)

    async def _open(self, pairing: Pairing) -> None:
        pid = pairing.phone_id
        phone = PhoneInfo(pid, pairing.phone_name, pairing.last_host or "", pairing.last_port or 0, True, "link")
        try:
            sig = await SignalingSession.connect(phone, pairing, self._ident)
        except SignalingError as exc:
            if exc.reason in REFUSALS:
                self._refused[pid] = pairing.token
            return
        finally:
            self._connecting.discard(pid)
        if self._store.get(pid) is None:  # oublié pendant la connexion
            await sig.close()
            return
        self._keep(pid, sig)
        self._notify()

    def _keep(self, pid: str, sig: SignalingSession) -> None:
        self._idle[pid] = (sig, self._spawn(self._watch(pid, sig)))

    async def _watch(self, pid: str, sig: SignalingSession) -> None:
        """Lit la connexion au repos : détecte la coupure et un éventuel refus du téléphone."""
        try:
            while True:
                msg = await sig.recv()
                if msg["type"] == "denied":
                    pairing = self._store.get(pid)
                    if pairing is not None:
                        self._refused[pid] = pairing.token
                    break
        except SignalingError:
            pass
        if self._idle.get(pid, (None,))[0] is sig:
            del self._idle[pid]
            with contextlib.suppress(Exception):
                await sig.close()
            self._notify()

    # --- prêt à une session de vue
    def take(self, phone_id: str) -> SignalingSession | None:
        entry = self._idle.pop(phone_id, None)
        if entry is None:
            return None
        sig, watcher = entry
        watcher.cancel()  # annuler une lecture en attente ne perd aucun message
        self._lent[phone_id] = sig
        return sig

    def give_back(self, phone_id: str, sig: SignalingSession) -> None:
        if self._lent.get(phone_id) is sig:
            del self._lent[phone_id]
        if sig.is_open and self._store.get(phone_id) is not None and phone_id not in self._idle:
            self._keep(phone_id, sig)
        else:
            self._spawn(sig.close())
        self._notify()

    async def forget(self, phone_id: str) -> None:
        self._refused.pop(phone_id, None)
        entry = self._idle.pop(phone_id, None)
        if entry is not None:
            sig, watcher = entry
            watcher.cancel()
            with contextlib.suppress(Exception):
                await sig.close()
        self._lent.pop(phone_id, None)
        self._notify()
