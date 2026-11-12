import asyncio

import pytest

from eyes_pc.viewer import ViewerSession, ViewerState
from fake_media import FakeMedia


class Recorder:
    def __init__(self):
        self.frames = 0
        self.audio = 0
        self.states: list[tuple[ViewerState, str | None]] = []
        self.event = asyncio.Event()

    def on_frame(self, f):
        self.frames += 1

    def on_audio(self, f):
        self.audio += 1

    def on_state(self, state, message):
        self.states.append((state, message))
        self.event.set()

    async def wait_for(self, cond, timeout=10):
        async def loop():
            while not cond():
                await asyncio.sleep(0.05)
        await asyncio.wait_for(loop(), timeout)


@pytest.fixture
async def media(paired_fake_phone):
    phone, _ = paired_fake_phone
    m = FakeMedia()
    phone.media_handler = m
    yield m
    await m.close()


def _viewer(paired_fake_phone, pc_ident, rec):
    phone, pairing = paired_fake_phone
    return ViewerSession(phone.info, pairing, pc_ident, rec.on_frame, rec.on_audio, rec.on_state)


async def test_receives_video_and_audio_frames(paired_fake_phone, pc_ident, media):
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await rec.wait_for(lambda: rec.frames >= 5 and rec.audio >= 1)
    assert (ViewerState.LIVE, None) in rec.states
    await v.stop()


async def test_set_camera_sends_set_message(paired_fake_phone, pc_ident, media):
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await v.set(camera="front")
    await rec.wait_for(lambda: media.sets)
    assert media.sets == [{"type": "set", "camera": "front"}]
    await v.stop()


async def test_stop_sends_stop_and_closes(paired_fake_phone, pc_ident, media):
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await v.stop()
    await asyncio.wait_for(media.stopped.wait(), 5)
    assert rec.states[-1][0] is ViewerState.STOPPED


async def test_connection_drop_goes_lost(paired_fake_phone, pc_ident, media):
    phone, _ = paired_fake_phone
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await rec.wait_for(lambda: rec.frames >= 1)
    await phone.drop_all()
    await rec.wait_for(lambda: rec.states[-1][0] is ViewerState.LOST, timeout=5)
    await v.stop()


async def test_busy_error_goes_error_with_message(paired_fake_phone, pc_ident):
    phone, _ = paired_fake_phone
    phone.media_handler = FakeMedia(error_code="busy")
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await rec.wait_for(lambda: rec.states and rec.states[-1][0] is ViewerState.ERROR)
    assert rec.states[-1][1] == "Ce téléphone est déjà regardé par un autre PC"


async def test_camera_busy_message(paired_fake_phone, pc_ident):
    phone, _ = paired_fake_phone
    phone.media_handler = FakeMedia(error_code="camera_busy")
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await rec.wait_for(lambda: rec.states and rec.states[-1][0] is ViewerState.ERROR)
    assert rec.states[-1][1] == "La caméra du téléphone est utilisée par une autre application"


async def test_revoked_gives_error(paired_fake_phone, pc_ident):
    phone, _ = paired_fake_phone
    phone.revoke("pc-test")
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    await v.start()
    await rec.wait_for(lambda: rec.states and rec.states[-1][0] is ViewerState.ERROR)
    assert "retiré" in rec.states[-1][1]


async def test_stop_during_connecting_does_not_stream(paired_fake_phone, pc_ident, media):
    phone, _ = paired_fake_phone
    rec = Recorder()
    v = _viewer(paired_fake_phone, pc_ident, rec)
    starting = asyncio.ensure_future(v.start())
    await asyncio.sleep(0)  # la connexion est en cours
    await v.stop()
    await starting
    await asyncio.sleep(1.0)
    assert rec.frames == 0
    assert media.pc is None or media.stopped.is_set()
    assert rec.states[-1][0] is ViewerState.STOPPED


def test_media_error_message():
    from eyes_pc.viewer import message_for
    assert message_for("media_error") == "Le téléphone n'a pas pu démarrer la caméra ou le micro"
