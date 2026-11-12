"""Point d'entrée d'Eyes PC : relie la découverte, le jumelage, les sessions de vue et l'interface."""

from __future__ import annotations

import asyncio
import logging
import os
import sys

import qasync
from PySide6.QtWidgets import QApplication

from .audio_out import AudioOutput, AudioRouter
from .discovery import Discovery, PhoneInfo
from .pairings import PairingStore, default_store_path, pc_identity
from .signaling import SignalingError, pair
from .ui.main_window import MainWindow
from .ui.pair_dialog import PairDialog
from .ui.view_window import ViewWindow
from .viewer import ViewerSession

log = logging.getLogger("eyes_pc")


class Controller:
    def __init__(self, app: QApplication) -> None:
        self.app = app
        self.store = PairingStore(default_store_path())
        self.ident = pc_identity()
        self.router = AudioRouter()
        self.audio = AudioOutput(self.router)
        self.main = MainWindow()
        self.view = ViewWindow(self.router, self._make_session, run=self._run)
        self.discovery = Discovery(self.store, self.main.set_phones)
        self._tasks: set[asyncio.Task] = set()
        self.main.pair_requested.connect(self._ask_code)
        self.main.view_requested.connect(self._view)
        self.main.forget_requested.connect(self._forget)

    def _run(self, coro) -> None:
        t = asyncio.ensure_future(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)

    def _make_session(self, phone, pairing, on_frame, on_audio, on_state) -> ViewerSession:
        return ViewerSession(phone, pairing, self.ident, on_frame, on_audio, on_state)

    # --- jumelage
    def _ask_code(self, phone: PhoneInfo, error: str | None = None) -> None:
        dialog = PairDialog(phone.name, self.main)
        if error:
            dialog.set_error(error)
        dialog.accepted.connect(lambda: self._run(self._pair(phone, dialog.code())))
        dialog.open()

    async def _pair(self, phone: PhoneInfo, code: str) -> None:
        self.main.show_status("Jumelage en cours…")
        try:
            await pair(phone, code, self.store, self.ident)
        except SignalingError as exc:
            self.main.show_status("")
            self._ask_code(phone, exc.reason)
            return
        self.main.show_status(f"{phone.name} est jumelé.")
        self.main.set_phones(self.discovery.phones())

    # --- vue
    def _view(self, phone: PhoneInfo) -> None:
        pairing = self.store.get(phone.phone_id)
        if pairing is None:
            self._ask_code(phone)
            return
        self.view.add_phone(phone, pairing)
        self.view.show()
        self.view.raise_()
        self.view.activateWindow()

    def _forget(self, phone: PhoneInfo) -> None:
        self.view.remove_phone(phone.phone_id)
        self.store.remove(phone.phone_id)
        self.main.set_phones(self.discovery.phones())

    async def run(self) -> None:
        closed = asyncio.Event()
        # On quitte quand la fenêtre principale se ferme, même si la vue est encore ouverte.
        self.main.closed.connect(closed.set)
        try:
            self.audio.start()
        except Exception:
            log.exception("sortie audio")
            self.main.show_status("Aucune sortie audio disponible : le son est désactivé.")
        self.main.set_phones([])
        self.main.show()
        await self.discovery.start()
        try:
            await closed.wait()
        finally:
            self.view.stop_all()
            self.view.close()
            await asyncio.sleep(0.5)  # laisse partir les messages `stop`
            await self.discovery.stop()
            self.audio.stop()


def main() -> int:
    logging.basicConfig(level=os.environ.get("EYES_LOG", "WARNING"))
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    loop = qasync.QEventLoop(app)
    asyncio.set_event_loop(loop)
    controller = Controller(app)
    with loop:
        loop.run_until_complete(controller.run())
    return 0


if __name__ == "__main__":
    sys.exit(main())
