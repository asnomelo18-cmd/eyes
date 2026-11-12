import pytest

from eyes_pc.pairings import PairingStore
from fake_phone import FakePhone


@pytest.fixture
def store(tmp_path):
    return PairingStore(tmp_path / "pairings.json")


@pytest.fixture
async def fake_phone():
    phone = FakePhone()
    await phone.start()
    yield phone
    await phone.stop()


@pytest.fixture
def pc_ident():
    return ("pc-test", "PC test")


@pytest.fixture
async def paired_fake_phone(fake_phone, store, pc_ident):
    from eyes_pc.signaling import pair

    pairing = await pair(fake_phone.info, fake_phone.code, store, pc_ident)
    return fake_phone, pairing
