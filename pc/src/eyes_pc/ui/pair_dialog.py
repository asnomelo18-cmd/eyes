"""Boîte de saisie du code de jumelage."""

from __future__ import annotations

from PySide6.QtCore import QRegularExpression
from PySide6.QtGui import QRegularExpressionValidator
from PySide6.QtWidgets import QDialog, QDialogButtonBox, QLabel, QLineEdit, QVBoxLayout

PAIR_ERRORS = {
    "bad_code": "Code incorrect",
    "code_expired": "Code expiré, affichez un nouveau code",
    "not_pairing": "Ouvrez l'écran « Jumeler un PC » sur le téléphone",
    "unreachable": "Téléphone introuvable sur le Wi-Fi",
}


def pair_error_text(reason: str) -> str:
    return PAIR_ERRORS.get(reason, f"Jumelage impossible ({reason})")


class PairDialog(QDialog):
    def __init__(self, phone_name: str, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle(f"Jumeler {phone_name}")
        self.code_edit = QLineEdit()
        self.code_edit.setValidator(QRegularExpressionValidator(QRegularExpression(r"\d{0,6}")))
        self.code_edit.setPlaceholderText("123456")
        self.code_edit.setStyleSheet("font-size: 22px; letter-spacing: 6px;")
        self.error_label = QLabel("")
        self.error_label.setStyleSheet("color: #a4262c;")
        self.buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        self.buttons.button(QDialogButtonBox.Ok).setText("Jumeler")
        self.buttons.button(QDialogButtonBox.Cancel).setText("Annuler")
        self.buttons.accepted.connect(self._accept_if_valid)
        self.buttons.rejected.connect(self.reject)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Tapez le code affiché sur le téléphone"))
        layout.addWidget(self.code_edit)
        layout.addWidget(self.error_label)
        layout.addWidget(self.buttons)

    def code(self) -> str:
        return self.code_edit.text()

    def _accept_if_valid(self) -> None:
        if len(self.code()) == 6:
            self.accept()
        else:
            self.error_label.setText("Le code a 6 chiffres")

    def set_error(self, reason: str) -> None:
        self.error_label.setText(pair_error_text(reason))
