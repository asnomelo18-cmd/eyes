import asyncio
import socket
import time

from zeroconf import ServiceInfo

from eyes_pc.discovery import SERVICE_TYPE, parse_service, probe_last_ip
from eyes_pc.pairings import Pairing


def _info(props, name="Pixel de Marie"):
    return ServiceInfo(
        SERVICE_TYPE,
        f"{name}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton("192.168.1.20")],
        port=40123,
        properties=props,
    )


def test_parse_service_valid():
    p = parse_service(_info({b"id": b"ph1", b"v": b"1"}))
    assert (p.phone_id, p.name, p.host, p.port, p.paired, p.via) == (
        "ph1", "Pixel de Marie", "192.168.1.20", 40123, False, "mdns")


def test_parse_service_wrong_version_ignored():
    assert parse_service(_info({b"id": b"ph1", b"v": b"2"})) is None


def test_parse_service_missing_id_ignored():
    assert parse_service(_info({b"v": b"1"})) is None


def test_paired_flag_from_store():
    p = parse_service(_info({b"id": b"ph1", b"v": b"1"}), paired_ids={"ph1"})
    assert p.paired is True


def _pairing(host, port):
    return Pairing("ph1", "Tel", "tok", "00", host, port)


async def test_probe_last_ip_success():
    server = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    async with server:
        p = await probe_last_ip(_pairing("127.0.0.1", port))
    assert (p.phone_id, p.host, p.port, p.paired, p.via) == ("ph1", "127.0.0.1", port, True, "last_ip")


async def test_probe_last_ip_unreachable_returns_none():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    t0 = time.monotonic()
    assert await probe_last_ip(_pairing("127.0.0.1", port)) is None
    assert time.monotonic() - t0 < 3


async def test_probe_without_address_returns_none():
    assert await probe_last_ip(_pairing(None, None)) is None


async def test_discovery_finds_announced_phone(tmp_path):
    from zeroconf.asyncio import AsyncZeroconf

    from eyes_pc.discovery import Discovery
    from eyes_pc.pairings import PairingStore

    found = asyncio.Event()
    seen = []

    def on_change(phones):
        seen[:] = phones
        if any(p.phone_id == "test-ph" for p in phones):
            found.set()

    azc = AsyncZeroconf()
    info = ServiceInfo(SERVICE_TYPE, f"Test Eyes.{SERVICE_TYPE}",
                       addresses=[socket.inet_aton("127.0.0.1")], port=40999,
                       properties={b"id": b"test-ph", b"v": b"1"})
    await azc.async_register_service(info)
    d = Discovery(PairingStore(tmp_path / "p.json"), on_change)
    try:
        await d.start()
        await asyncio.wait_for(found.wait(), 10)
    finally:
        await d.stop()
        await azc.async_unregister_service(info)
        await azc.async_close()
    assert any(p.name == "Test Eyes" and p.port == 40999 for p in seen)


def test_unreachable_paired_phone_is_listed_offline(tmp_path):
    from eyes_pc.discovery import Discovery
    from eyes_pc.pairings import PairingStore

    store = PairingStore(tmp_path / "p.json")
    store.save(Pairing("ph9", "Tel absent", "tok", "00", "10.0.0.9", 4000))
    d = Discovery(store, lambda phones: None)
    phones = d.phones()
    assert [(p.phone_id, p.paired, p.via) for p in phones] == [("ph9", True, "none")]
