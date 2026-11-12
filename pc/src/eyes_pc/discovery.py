"""Découverte des téléphones Eyes sur le Wi-Fi (mDNS) avec repli sur la dernière IP connue."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Literal

from zeroconf import IPVersion, ServiceInfo, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf

from .pairings import Pairing, PairingStore
from .protocol import PROTOCOL_VERSION

log = logging.getLogger(__name__)

SERVICE_TYPE = "_eyes._tcp.local."
FALLBACK_AFTER_S = 5.0
PROBE_TIMEOUT_S = 2.0


@dataclass(frozen=True)
class PhoneInfo:
    phone_id: str
    name: str
    host: str
    port: int
    paired: bool
    via: Literal["mdns", "last_ip", "link", "none"]  # "link" : connexion maintenue ; "none" : introuvable


def parse_service(info: ServiceInfo, paired_ids: Iterable[str] = frozenset()) -> PhoneInfo | None:
    props = info.properties or {}
    raw_id = props.get(b"id")
    raw_v = props.get(b"v")
    if not raw_id or raw_v != str(PROTOCOL_VERSION).encode():
        return None
    addrs = info.parsed_addresses(IPVersion.V4Only) or info.parsed_addresses()
    if not addrs or not info.port:
        return None
    phone_id = raw_id.decode("utf-8", "replace")
    name = info.name
    if name.endswith("." + SERVICE_TYPE):
        name = name[: -len(SERVICE_TYPE) - 1]
    return PhoneInfo(phone_id, name, addrs[0], int(info.port), phone_id in set(paired_ids), "mdns")


async def probe_last_ip(p: Pairing, timeout: float = PROBE_TIMEOUT_S) -> PhoneInfo | None:
    if not p.last_host or not p.last_port:
        return None
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(p.last_host, p.last_port), timeout)
    except (OSError, asyncio.TimeoutError):
        return None
    writer.close()
    with contextlib.suppress(Exception):
        await writer.wait_closed()
    return PhoneInfo(p.phone_id, p.phone_name, p.last_host, p.last_port, True, "last_ip")


class Discovery:
    """Tient à jour la liste des téléphones visibles et appelle on_change à chaque changement."""

    def __init__(self, store: PairingStore, on_change: Callable[[list[PhoneInfo]], None]) -> None:
        self._store = store
        self._on_change = on_change
        self._mdns: dict[str, PhoneInfo] = {}  # nom de service -> téléphone
        self._fallback: dict[str, PhoneInfo] = {}  # phone_id -> téléphone trouvé par IP
        self._azc: AsyncZeroconf | None = None
        self._browser: AsyncServiceBrowser | None = None
        self._tasks: set[asyncio.Task] = set()
        self._started_at = 0.0

    def phones(self) -> list[PhoneInfo]:
        pairings = self._store.load()
        paired = set(pairings)
        by_id: dict[str, PhoneInfo] = {}
        for pr in pairings.values():  # toujours listés, même introuvables
            by_id[pr.phone_id] = PhoneInfo(pr.phone_id, pr.phone_name, pr.last_host or "", pr.last_port or 0,
                                           True, "none")
        for p in self._fallback.values():
            by_id[p.phone_id] = p
        for p in self._mdns.values():
            by_id[p.phone_id] = PhoneInfo(p.phone_id, p.name, p.host, p.port, p.phone_id in paired, "mdns")
        return sorted(by_id.values(), key=lambda p: (not p.paired, p.name.lower()))

    def _notify(self) -> None:
        try:
            self._on_change(self.phones())
        except Exception:  # l'interface ne doit pas casser la découverte
            log.exception("on_change a échoué")

    async def start(self) -> None:
        self._started_at = time.monotonic()
        self._azc = AsyncZeroconf(ip_version=IPVersion.V4Only)
        self._browser = AsyncServiceBrowser(
            self._azc.zeroconf, SERVICE_TYPE, handlers=[self._on_service_state_change]
        )
        self._spawn(self._fallback_loop())

    async def stop(self) -> None:
        for t in list(self._tasks):
            t.cancel()
        if self._browser:
            await self._browser.async_cancel()
        if self._azc:
            await self._azc.async_close()
        self._browser = self._azc = None

    def _spawn(self, coro) -> None:
        t = asyncio.ensure_future(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)

    def _on_service_state_change(self, zeroconf, service_type, name, state_change) -> None:
        if state_change is ServiceStateChange.Removed:
            if self._mdns.pop(name, None) is not None:
                self._notify()
            return
        self._spawn(self._resolve(name))

    async def _resolve(self, name: str) -> None:
        if not self._azc:
            return
        info = AsyncServiceInfo(SERVICE_TYPE, name)
        if not await info.async_request(self._azc.zeroconf, 3000):
            return
        phone = parse_service(info, self._store.load())
        if phone is None:
            return
        self._mdns[name] = phone
        self._fallback.pop(phone.phone_id, None)
        if phone.phone_id in self._store.load():
            self._store.update_address(phone.phone_id, phone.host, phone.port)
        self._notify()

    async def _fallback_loop(self) -> None:
        await asyncio.sleep(FALLBACK_AFTER_S)
        while True:
            seen = {p.phone_id for p in self._mdns.values()}
            changed = False
            for pairing in self._store.load().values():
                if pairing.phone_id in seen:
                    continue
                found = await probe_last_ip(pairing)
                if found and pairing.phone_id not in self._fallback:
                    self._fallback[pairing.phone_id] = found
                    changed = True
                elif not found and self._fallback.pop(pairing.phone_id, None):
                    changed = True
            if changed:
                self._notify()
            await asyncio.sleep(FALLBACK_AFTER_S)
