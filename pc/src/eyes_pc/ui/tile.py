"""Tuile d'un téléphone dans la fenêtre de vue."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtWidgets import QFrame, QHBoxLayout, QLabel, QPushButton, QSizePolicy, QVBoxLayout

from ..viewer import ViewerState

STATE_TEXT = {
    ViewerState.CONNECTING: "Connexion…",
    ViewerState.LIVE: "En direct",
    ViewerState.STOPPED: "Arrêté",
}


class PhoneTile(QFrame):
    clicked = Signal(str)
    camera_toggled = Signal(str, bool)
    mic_toggled = Signal(str, bool)
    flip_requested = Signal(str, str)
    stop_requested = Signal(str)
    reconnect_requested = Signal(str)

    def __init__(self, phone_id: str, name: str, parent=None) -> None:
        super().__init__(parent)
        self.phone_id = phone_id
        self.camera = "back"
        self.selected = False
        self.state: ViewerState = ViewerState.CONNECTING
        self._pixmap: QPixmap | None = None
        self.setFrameShape(QFrame.Box)
        self.setLineWidth(2)

        self.name_label = QLabel(name)
        self.name_label.setStyleSheet("font-weight: bold;")
        self.video = QLabel()
        self.video.setAlignment(Qt.AlignCenter)
        self.video.setMinimumSize(240, 135)
        self.video.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Ignored)
        self.video.setStyleSheet("background: black; color: white;")
        self.status_label = QLabel(STATE_TEXT[ViewerState.CONNECTING])
        self.status_label.setWordWrap(True)

        self.camera_btn = QPushButton("Caméra")
        self.mic_btn = QPushButton("Micro")
        for b in (self.camera_btn, self.mic_btn):
            b.setCheckable(True)
            b.setChecked(True)
        self.flip_btn = QPushButton("Avant/Arrière")
        self.stop_btn = QPushButton("Arrêter")
        self.reconnect_btn = QPushButton("Reconnecter")
        self.reconnect_btn.hide()

        self.camera_btn.toggled.connect(lambda on: self.camera_toggled.emit(self.phone_id, on))
        self.mic_btn.toggled.connect(lambda on: self.mic_toggled.emit(self.phone_id, on))
        self.flip_btn.clicked.connect(self._flip)
        self.stop_btn.clicked.connect(lambda: self.stop_requested.emit(self.phone_id))
        self.reconnect_btn.clicked.connect(lambda: self.reconnect_requested.emit(self.phone_id))

        buttons = QHBoxLayout()
        for b in (self.camera_btn, self.mic_btn, self.flip_btn, self.stop_btn):
            buttons.addWidget(b)
        layout = QVBoxLayout(self)
        layout.addWidget(self.name_label)
        layout.addWidget(self.video, 1)
        status = QHBoxLayout()
        status.addWidget(self.status_label, 1)
        status.addWidget(self.reconnect_btn)
        layout.addLayout(status)
        layout.addLayout(buttons)
        self.set_selected(False)

    def _flip(self) -> None:
        self.camera = "front" if self.camera == "back" else "back"
        self.flip_requested.emit(self.phone_id, self.camera)

    def mousePressEvent(self, event) -> None:  # noqa: N802 (API Qt)
        self.clicked.emit(self.phone_id)
        super().mousePressEvent(event)

    def set_selected(self, selected: bool) -> None:
        self.selected = selected
        self.setStyleSheet("PhoneTile { border: 3px solid #2e7dd7; }" if selected
                           else "PhoneTile { border: 3px solid transparent; }")
        self.name_label.setText(self.name_label.text().removesuffix(" 🔊") + (" 🔊" if selected else ""))

    def set_state(self, state: ViewerState, message: str | None) -> None:
        self.state = state
        self.status_label.setText(message or STATE_TEXT.get(state, ""))
        self.reconnect_btn.setVisible(state in (ViewerState.LOST, ViewerState.ERROR))
        if state is ViewerState.CONNECTING:
            self.camera_btn.setChecked(True)
            self.mic_btn.setChecked(True)
            self.camera = "back"

    def show_image(self, image: QImage) -> None:
        self._pixmap = QPixmap.fromImage(image)
        self._rescale()

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._rescale()

    def _rescale(self) -> None:
        if self._pixmap is not None:
            self.video.setPixmap(self._pixmap.scaled(self.video.size(), Qt.KeepAspectRatio,
                                                     Qt.SmoothTransformation))
