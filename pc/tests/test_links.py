"""Connexions maintenues ouvertes avec les téléphones jumelés (le Huawei refuse toute nouvelle
connexion entrante sur batterie écran éteint, mais garde celles déjà ouvertes)."""

import asyncio

import pytest

from eyes_pc.links import LinkPool
from eyes_pc.viewer import ViewerSession, ViewerState
from fake_media import FakeMedia
from test_viewer import Recorder


async def until(cond, timeout=5.0):
    async def loop():
        while not cond():
            await asyncio.sleep(0.02)
    await asyncio.wait_for(loop(), timeout)


def hellos(phone):
    return sum(1 for m in phone.received if m["type"] == "hello")


@pytest.fixture
async def pool(paired_fake_phone, store, pc_ident):
    changes = []
    p = LinkPool(store, pc_ident, on_change=lambda: changes.append(1), retry_s=0.1)
    p.changes = changes
    await p.start()
    yield p
    await p.stop()


async def test_opens_link_to_paired_phone(paired_fake_phone, pool):
    phone, pairing = paired_fake_phone
    await until(lambda: pairing.phone_id in pool.linked())
    assert hellos(phone) == 1
    assert pool.changes  # la fenêtre principale est prévenue


async def test_take_and_give_back_keep_the_same_connection(paired_fake_phone, pool):
    phone, pairing = paired_fake_phone
    await until(lambda: pairing.phone_id in pool.linked())
    sig = pool.take(pairing.phone_id)
    assert sig is not None
    assert pool.take(pairing.phone_id) is None  # déjà prêtée
    pool.give_back(pairing.phone_id, sig)
    await asyncio.sleep(0.3)
    assert pairing.phone_id in pool.linked()
    assert hellos(phone) == 1  # aucune nouvelle connexion


async def test_link_reopens_after_drop(paired_fake_phone, pool):
    phone, pairing = paired_fake_phone
    await until(lambda: pairing.phone_id in pool.linked())
    await phone.drop_all()
    await until(lambda: hellos(phone) == 2)
    await until(lambda: pairing.phone_id in pool.linked())


async def test_revoked_phone_is_not_retried(paired_fake_phone, pool):
    phone, pairing = paired_fake_phone
    await until(lambda: pairing.phone_id in pool.linked())
    phone.revoke("pc-test")
    await phone.drop_all()
    await until(lambda: hellos(phone) == 2)  # une tentative, refusée
    await asyncio.sleep(0.5)
    assert hellos(phone) == 2  # refusé une fois : on n'insiste pas
    assert pairing.phone_id not in pool.linked()


async def test_forget_closes_link(paired_fake_phone, pool):
    phone, pairing = paired_fake_phone
    await until(lambda: pairing.phone_id in pool.linked())
    await pool.forget(pairing.phone_id)
    assert pairing.phone_id not in pool.linked()
    await until(lambda: not phone.connections)


async def test_viewer_uses_kept_link_and_returns_it(paired_fake_phone, pc_ident, pool):
    phone, pairing = paired_fake_phone
    media = FakeMedia()
    phone.media_handler = media
    await until(lambda: pairing.phone_id in pool.linked())
    for _ in range(2):  # deux vues de suite sur la même connexion
        rec = Recorder()
        v = ViewerSession(phone.info, pairing, pc_ident, rec.on_frame, rec.on_audio, rec.on_state,
                          links=pool)
        await v.start()
        await rec.wait_for(lambda: rec.frames >= 3)
        await v.stop()
        await until(lambda: pairing.phone_id in pool.linked())
    await media.close()
    assert hellos(phone) == 1


async def test_viewer_without_link_opens_its_own_connection(paired_fake_phone, pc_ident, store):
    phone, pairing = paired_fake_phone
    media = FakeMedia()
    phone.media_handler = media
    empty = LinkPool(store, pc_ident, retry_s=60)  # pas démarré : aucune connexion maintenue
    rec = Recorder()
    v = ViewerSession(phone.info, pairing, pc_ident, rec.on_frame, rec.on_audio, rec.on_state, links=empty)
    await v.start()
    await rec.wait_for(lambda: rec.frames >= 1)
    await v.stop()
    await media.close()
    assert (ViewerState.LIVE, None) in rec.states


def test_with_links_marks_unseen_linked_phones_reachable():
    from eyes_pc.discovery import PhoneInfo
    from eyes_pc.links import with_links
    unseen = PhoneInfo("a", "A", "10.0.0.2", 4000, True, "none")
    seen = PhoneInfo("b", "B", "10.0.0.3", 4000, True, "mdns")
    other = PhoneInfo("c", "C", "", 0, True, "none")
    out = with_links([unseen, seen, other], {"a", "b"})
    assert [p.via for p in out] == ["link", "mdns", "none"]
