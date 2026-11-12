"""Fenêtre principale : téléphones trouvés sur le Wi-Fi."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

from ..discovery import PhoneInfo

EMPTY_TEXT = ("Aucun téléphone trouvé. Ouvrez Eyes sur le téléphone et activez "
              "« Prêt à partager ».")


class MainWindow(QWidget):
    pair_requested = Signal(object)
    view_requested = Signal(object)
    forget_requested = Signal(object)
    closed = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Eyes")
        self.resize(460, 360)
        title = QLabel("Téléphones sur le Wi-Fi")
        title.setStyleSheet("font-size: 16px; font-weight: bold;")
        self.help_label = QLabel(EMPTY_TEXT)
        self.help_label.setWordWrap(True)
        self.status_label = QLabel("")
        self.status_label.setWordWrap(True)
        self.status_label.setStyleSheet("color: #a4262c;")
        self._rows = QVBoxLayout()
        self._buttons: dict[str, QPushButton] = {}
        self._labels: dict[str, QLabel] = {}
        self._row_widgets: list[QWidget] = []
        layout = QVBoxLayout(self)
        layout.addWidget(title)
        layout.addWidget(self.help_label)
        layout.addLayout(self._rows)
        layout.addStretch(1)
        layout.addWidget(self.status_label)

    def closeEvent(self, event) -> None:  # noqa: N802
        super().closeEvent(event)
        self.closed.emit()

    def row_text(self, phone_id: str) -> str:
        return self._labels[phone_id].text()

    def action_button(self, phone_id: str) -> QPushButton:
        return self._buttons[phone_id]

    def show_status(self, text: str) -> None:
        self.status_label.setText(text)

    def set_phones(self, phones: list[PhoneInfo]) -> None:
        for w in self._row_widgets:
            self._rows.removeWidget(w)
            w.deleteLater()
        self._row_widgets.clear()
        self._buttons.clear()
        self._labels.clear()
        self.help_label.setVisible(not phones)
        for phone in phones:
            row = QWidget()
            h = QHBoxLayout(row)
            h.setContentsMargins(0, 2, 0, 2)
            if phone.via == "none":
                status = "Téléphone introuvable sur le Wi-Fi"
            else:
                status = "Jumelé" if phone.paired else "Non jumelé"
            label = QLabel(f"{phone.name}  —  {status}")
            label.setWordWrap(True)
            self._labels[phone.phone_id] = label
            h.addWidget(label, 1)
            if phone.paired:
                forget = QPushButton("Oublier")
                forget.setFlat(True)
                forget.setCursor(Qt.PointingHandCursor)
                forget.clicked.connect(lambda _=False, p=phone: self.forget_requested.emit(p))
                h.addWidget(forget)
                btn = QPushButton("Voir")
                btn.setEnabled(phone.via != "none")
                btn.clicked.connect(lambda _=False, p=phone: self.view_requested.emit(p))
            else:
                btn = QPushButton("Jumeler")
                btn.clicked.connect(lambda _=False, p=phone: self.pair_requested.emit(p))
            h.addWidget(btn)
            self._buttons[phone.phone_id] = btn
            self._rows.addWidget(row)
            self._row_widgets.append(row)
