import pytest
from PySide6.QtCore import Qt

from eyes_pc.audio_out import AudioRouter
from eyes_pc.discovery import PhoneInfo
from eyes_pc.pairings import Pairing
from eyes_pc.ui.main_window import EMPTY_TEXT, MainWindow
from eyes_pc.ui.view_window import WARNING_TEXT, ViewWindow, grid_shape
from eyes_pc.viewer import ViewerState


def phone(i, paired=True):
    return PhoneInfo(f"ph{i}", f"Téléphone {i}", "10.0.0.%d" % i, 4000 + i, paired, "mdns")


def pairing(i):
    return Pairing(f"ph{i}", f"Téléphone {i}", "tok", "00", None, None)


class FakeSession:
    def __init__(self, phone, on_state):
        self.phone = phone
        self.on_state = on_state
        self.calls = []

    async def _noop(self):
        return None

    def start(self, **kw):
        self.calls.append(("start", kw))
        return self._noop()

    def set(self, **kw):
        self.calls.append(("set", kw))
        return self._noop()

    def stop(self):
        self.calls.append(("stop", {}))
        return self._noop()


@pytest.fixture
def view(qtbot):
    sessions = {}

    def factory(phone, pairing, on_frame, on_audio, on_state):
        s = FakeSession(phone, on_state)
        sessions.setdefault(phone.phone_id, []).append(s)
        return s

    router = AudioRouter()
    w = ViewWindow(router, factory, run=lambda coro: coro.close())
    qtbot.addWidget(w)
    w.sessions_by_phone = sessions
    return w, router, sessions


def test_empty_list_shows_help_text(qtbot):
    w = MainWindow()
    qtbot.addWidget(w)
    w.set_phones([])
    assert w.help_label.text() == EMPTY_TEXT and w.help_label.isVisibleTo(w)


def test_pair_button_for_unpaired_phone(qtbot):
    w = MainWindow()
    qtbot.addWidget(w)
    w.set_phones([phone(1, paired=False), phone(2, paired=True)])
    assert w.action_button("ph1").text() == "Jumeler"
    assert w.action_button("ph2").text() == "Voir"
    with qtbot.waitSignal(w.pair_requested) as sig:
        qtbot.mouseClick(w.action_button("ph1"), Qt.LeftButton)
    assert sig.args[0].phone_id == "ph1"
    with qtbot.waitSignal(w.view_requested) as sig:
        qtbot.mouseClick(w.action_button("ph2"), Qt.LeftButton)
    assert sig.args[0].phone_id == "ph2"


@pytest.mark.parametrize("n,shape", [(1, (1, 1)), (2, (2, 1)), (3, (2, 2)), (4, (2, 2)), (5, (3, 2)), (7, (3, 3))])
def test_grid_layout_for_counts(n, shape):
    assert grid_shape(n) == shape


def test_add_phone_starts_session(view):
    w, _, sessions = view
    w.add_phone(phone(1), pairing(1))
    assert sessions["ph1"][0].calls[0] == ("start", {"video": True, "audio": True, "camera": "back"})
    assert w.tile_count() == 1


def test_adding_same_phone_twice_keeps_one_tile(view):
    w, _, _ = view
    w.add_phone(phone(1), pairing(1))
    w.add_phone(phone(1), pairing(1))
    assert w.tile_count() == 1


def test_fifth_tile_shows_warning_once(view):
    w, _, _ = view
    for i in range(1, 5):
        w.add_phone(phone(i), pairing(i))
    assert not w.warning_label.isVisibleTo(w)
    w.add_phone(phone(5), pairing(5))
    assert w.warning_label.isVisibleTo(w) and w.warning_label.text() == WARNING_TEXT
    assert w.warnings_shown == 1
    w.remove_phone("ph5")
    w.add_phone(phone(5), pairing(5))
    assert w.warnings_shown == 1


def test_first_tile_is_audible_and_click_selects_audio(view, qtbot):
    w, router, _ = view
    w.add_phone(phone(1), pairing(1))
    w.add_phone(phone(2), pairing(2))
    assert router.selected == "ph1"
    qtbot.mouseClick(w.tile("ph2"), Qt.LeftButton)
    assert router.selected == "ph2"
    assert w.tile("ph2").selected and not w.tile("ph1").selected


def test_hear_all_checkbox(view):
    w, router, _ = view
    w.hear_all.setChecked(True)
    assert router.mode == "all"


def test_tile_controls_send_set(view, qtbot):
    w, _, sessions = view
    w.add_phone(phone(1), pairing(1))
    t = w.tile("ph1")
    qtbot.mouseClick(t.camera_btn, Qt.LeftButton)
    qtbot.mouseClick(t.mic_btn, Qt.LeftButton)
    qtbot.mouseClick(t.flip_btn, Qt.LeftButton)
    calls = sessions["ph1"][0].calls[1:]
    assert calls == [("set", {"video": False}), ("set", {"audio": False}), ("set", {"camera": "front"})]


def test_lost_tile_shows_reconnect_and_others_keep_running(view, qtbot):
    w, _, sessions = view
    w.add_phone(phone(1), pairing(1))
    w.add_phone(phone(2), pairing(2))
    sessions["ph1"][0].on_state(ViewerState.LOST, "Connexion perdue")
    t1, t2 = w.tile("ph1"), w.tile("ph2")
    assert t1.reconnect_btn.isVisibleTo(t1) and t1.status_label.text() == "Connexion perdue"
    assert not t2.reconnect_btn.isVisibleTo(t2)
    assert ("stop", {}) not in sessions["ph2"][0].calls
    qtbot.mouseClick(t1.reconnect_btn, Qt.LeftButton)
    assert len(sessions["ph1"]) == 2 and sessions["ph1"][1].calls[0][0] == "start"


def test_stop_button_removes_tile(view, qtbot):
    w, router, sessions = view
    w.add_phone(phone(1), pairing(1))
    qtbot.mouseClick(w.tile("ph1").stop_btn, Qt.LeftButton)
    assert w.tile_count() == 0 and ("stop", {}) in sessions["ph1"][0].calls
    assert router.selected is None


def test_main_window_emits_closed(qtbot):
    w = MainWindow()
    qtbot.addWidget(w)
    w.show()
    with qtbot.waitSignal(w.closed, timeout=1000):
        w.close()


def test_offline_paired_phone_shows_unreachable(qtbot):
    w = MainWindow()
    qtbot.addWidget(w)
    w.set_phones([PhoneInfo("ph1", "Tel", "", 0, True, "none")])
    assert "Téléphone introuvable sur le Wi-Fi" in w.row_text("ph1")
    assert not w.action_button("ph1").isEnabled()


def test_reconnect_uses_fresh_address(view, qtbot):
    w, _, sessions = view
    w.add_phone(phone(1), pairing(1))
    sessions["ph1"][0].on_state(ViewerState.LOST, "Connexion perdue")
    moved = PhoneInfo("ph1", "Téléphone 1", "10.0.0.99", 5555, True, "mdns")
    w.refresh_targets([moved])
    qtbot.mouseClick(w.tile("ph1").reconnect_btn, Qt.LeftButton)
    assert (sessions["ph1"][-1].phone.host, sessions["ph1"][-1].phone.port) == ("10.0.0.99", 5555)


def test_view_on_lost_tile_reconnects(view):
    w, _, sessions = view
    w.add_phone(phone(1), pairing(1))
    sessions["ph1"][0].on_state(ViewerState.LOST, "Connexion perdue")
    w.add_phone(phone(1), pairing(1))
    assert len(sessions["ph1"]) == 2 and w.tile_count() == 1
