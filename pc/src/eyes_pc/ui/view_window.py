"""Fenêtre de vue : grille de tuiles, une par téléphone regardé."""

from __future__ import annotations

import math
from collections.abc import Callable, Coroutine

from PySide6.QtCore import Qt
from PySide6.QtGui import QImage
from PySide6.QtWidgets import QCheckBox, QGridLayout, QHBoxLayout, QLabel, QVBoxLayout, QWidget

from ..audio_out import AudioRouter
from ..discovery import PhoneInfo
from ..pairings import Pairing
from ..viewer import ViewerState
from .tile import PhoneTile

WARNING_TEXT = "Au-delà de 4 téléphones, le PC ou le Wi-Fi peuvent ralentir."
WARN_ABOVE = 4

# (phone, pairing, on_frame, on_audio, on_state) -> session (start/set/stop renvoient des coroutines)
SessionFactory = Callable[..., object]


def grid_shape(n: int) -> tuple[int, int]:
    """(colonnes, lignes) pour n tuiles."""
    if n <= 1:
        return (1, 1)
    if n == 2:
        return (2, 1)
    if n <= 4:
        return (2, 2)
    return (3, math.ceil(n / 3))


def frame_to_qimage(frame) -> QImage:
    rgb = frame.to_ndarray(format="rgb24")
    h, w, _ = rgb.shape
    return QImage(rgb.data, w, h, 3 * w, QImage.Format_RGB888).copy()


class ViewWindow(QWidget):
    def __init__(self, router: AudioRouter, session_factory: SessionFactory,
                 run: Callable[[Coroutine], None]) -> None:
        super().__init__()
        self.setWindowTitle("Eyes — Vue")
        self.resize(1000, 650)
        self._router = router
        self._factory = session_factory
        self._run = run
        self._tiles: dict[str, PhoneTile] = {}
        self._sessions: dict[str, object] = {}
        self._targets: dict[str, tuple[PhoneInfo, Pairing]] = {}
        self.warnings_shown = 0

        self.warning_label = QLabel(WARNING_TEXT)
        self.warning_label.setStyleSheet("background: #fff4ce; color: #5c4400; padding: 6px;")
        self.warning_label.hide()
        self.hear_all = QCheckBox("Tout entendre")
        self.hear_all.toggled.connect(lambda on: self._router.set_mode("all" if on else "one"))
        hint = QLabel("Cliquez sur un téléphone pour l'entendre.")
        top = QHBoxLayout()
        top.addWidget(hint, 1)
        top.addWidget(self.hear_all)

        self._grid = QGridLayout()
        layout = QVBoxLayout(self)
        layout.addWidget(self.warning_label)
        layout.addLayout(top)
        layout.addLayout(self._grid, 1)

    # --- accès pour l'application et les tests
    def tile(self, phone_id: str) -> PhoneTile:
        return self._tiles[phone_id]

    def tile_count(self) -> int:
        return len(self._tiles)

    def has_phone(self, phone_id: str) -> bool:
        return phone_id in self._tiles

    # --- gestion des téléphones
    def add_phone(self, phone: PhoneInfo, pairing: Pairing) -> None:
        if phone.phone_id in self._tiles:
            return
        tile = PhoneTile(phone.phone_id, phone.name)
        tile.clicked.connect(self._select)
        tile.camera_toggled.connect(lambda pid, on: self._set(pid, video=on))
        tile.mic_toggled.connect(lambda pid, on: self._set(pid, audio=on))
        tile.flip_requested.connect(lambda pid, cam: self._set(pid, camera=cam))
        tile.stop_requested.connect(self.remove_phone)
        tile.reconnect_requested.connect(self._reconnect)
        self._tiles[phone.phone_id] = tile
        self._targets[phone.phone_id] = (phone, pairing)
        self._relayout()
        if self._router.selected is None:
            self._select(phone.phone_id)
        if len(self._tiles) > WARN_ABOVE and self.warnings_shown == 0:
            self.warnings_shown += 1
            self.warning_label.show()
        self._start_session(phone.phone_id)

    def _start_session(self, phone_id: str) -> None:
        phone, pairing = self._targets[phone_id]
        tile = self._tiles[phone_id]
        tile.set_state(ViewerState.CONNECTING, None)

        def on_frame(frame, tile=tile):
            tile.show_image(frame_to_qimage(frame))

        def on_audio(frame, pid=phone_id):
            self._router.push(pid, frame)

        def on_state(state, message, pid=phone_id):
            if pid in self._tiles and self._sessions.get(pid) is session:
                self._tiles[pid].set_state(state, message)

        session = self._factory(phone, pairing, on_frame, on_audio, on_state)
        self._sessions[phone_id] = session
        self._run(session.start(video=True, audio=True, camera="back"))

    def _reconnect(self, phone_id: str) -> None:
        old = self._sessions.pop(phone_id, None)
        if old is not None:
            self._run(old.stop())
        self._start_session(phone_id)

    def remove_phone(self, phone_id: str) -> None:
        session = self._sessions.pop(phone_id, None)
        if session is not None:
            self._run(session.stop())
        tile = self._tiles.pop(phone_id, None)
        self._targets.pop(phone_id, None)
        self._router.remove(phone_id)
        if tile is not None:
            self._grid.removeWidget(tile)
            tile.deleteLater()
        if self._router.selected is None and self._tiles:
            self._select(next(iter(self._tiles)))
        if len(self._tiles) <= WARN_ABOVE:
            self.warning_label.hide()
        self._relayout()

    def stop_all(self) -> None:
        for pid in list(self._tiles):
            self.remove_phone(pid)

    def _set(self, phone_id: str, **changes) -> None:
        session = self._sessions.get(phone_id)
        if session is not None:
            self._run(session.set(**changes))

    def _select(self, phone_id: str) -> None:
        self._router.select(phone_id)
        for pid, tile in self._tiles.items():
            tile.set_selected(pid == phone_id)

    def _relayout(self) -> None:
        for tile in self._tiles.values():
            self._grid.removeWidget(tile)
        cols, _ = grid_shape(len(self._tiles))
        for i, tile in enumerate(self._tiles.values()):
            self._grid.addWidget(tile, i // cols, i % cols)

    def closeEvent(self, event) -> None:  # noqa: N802
        self.stop_all()
        super().closeEvent(event)
