import pytest

from eyes_pc.discovery import PhoneInfo
from eyes_pc.protocol import start_msg
from eyes_pc.signaling import SignalingError, SignalingSession, pair


async def test_pair_stores_token_and_cert_fingerprint(fake_phone, store, pc_ident):
    p = await pair(fake_phone.info, fake_phone.code, store, pc_ident)
    assert p.token == fake_phone.issued_token
    assert p.cert_sha256 == fake_phone.cert_sha256
    assert (p.last_host, p.last_port) == ("127.0.0.1", fake_phone.port)
    assert store.get(fake_phone.phone_id) == p
    assert fake_phone.received[0] == {"type": "pair", "code": fake_phone.code, "pcId": "pc-test",
                                      "pcName": "PC test", "v": 1}


async def test_wrong_code_raises_bad_code(fake_phone, store, pc_ident):
    with pytest.raises(SignalingError) as e:
        await pair(fake_phone.info, "000000", store, pc_ident)
    assert e.value.reason == "bad_code"
    assert store.get(fake_phone.phone_id) is None


async def test_hello_with_valid_token_gets_welcome(paired_fake_phone, pc_ident):
    phone, pairing = paired_fake_phone
    s = await SignalingSession.connect(phone.info, pairing, pc_ident)
    await s.send(start_msg(True, True, "back"))
    await s.close()
    assert phone.received[-2]["type"] == "hello" and phone.received[-2]["token"] == pairing.token


async def test_revoked_raises_revoked(paired_fake_phone, pc_ident):
    phone, pairing = paired_fake_phone
    phone.revoke("pc-test")
    with pytest.raises(SignalingError) as e:
        await SignalingSession.connect(phone.info, pairing, pc_ident)
    assert e.value.reason == "revoked"


async def test_cert_change_is_refused(paired_fake_phone, pc_ident):
    phone, pairing = paired_fake_phone
    await phone.change_cert()
    n_before = len(phone.received)
    with pytest.raises(SignalingError) as e:
        await SignalingSession.connect(phone.info, pairing, pc_ident)
    assert e.value.reason == "cert_mismatch"
    assert len(phone.received) == n_before  # le jeton n'a pas été envoyé


async def test_unreachable_phone(store, pc_ident):
    with pytest.raises(SignalingError) as e:
        await pair(PhoneInfo("x", "x", "127.0.0.1", 1, False, "mdns"), "123456", store, pc_ident)
    assert e.value.reason == "unreachable"


async def test_connect_retries_when_sleeping_phone_drops_first_attempt(paired_fake_phone, pc_ident, monkeypatch):
    # Un téléphone en veille perd parfois la première tentative : on réessaie avant d'abandonner.
    import eyes_pc.signaling as sig
    phone, pairing = paired_fake_phone
    real_connect, calls = sig.connect, []

    def flaky_connect(*args, **kwargs):
        calls.append(1)
        if len(calls) == 1:
            raise OSError("paquet perdu")
        return real_connect(*args, **kwargs)

    monkeypatch.setattr(sig, "connect", flaky_connect)
    monkeypatch.setattr(sig, "RETRY_DELAY_S", 0.01)
    s = await SignalingSession.connect(phone.info, pairing, pc_ident)
    await s.close()
    assert len(calls) == 2


async def test_unreachable_after_all_attempts(store, pc_ident, monkeypatch):
    import eyes_pc.signaling as sig
    monkeypatch.setattr(sig, "RETRY_DELAY_S", 0.01)
    calls = []
    real_connect = sig.connect
    monkeypatch.setattr(sig, "connect", lambda *a, **k: (calls.append(1), real_connect(*a, **k))[1])
    with pytest.raises(SignalingError) as e:
        await pair(PhoneInfo("x", "x", "127.0.0.1", 1, False, "mdns"), "123456", store, pc_ident)
    assert e.value.reason == "unreachable" and len(calls) == sig.CONNECT_ATTEMPTS
