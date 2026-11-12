"""Mémorisation des téléphones jumelés et de l'identité du PC."""

from __future__ import annotations

import json
import os
import platform
import uuid
from dataclasses import asdict, dataclass, replace
from pathlib import Path


@dataclass(frozen=True)
class Pairing:
    phone_id: str
    phone_name: str
    token: str
    cert_sha256: str
    last_host: str | None
    last_port: int | None


def app_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "Eyes"


def default_store_path() -> Path:
    return app_dir() / "pairings.json"


def _write_atomic(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, path)


class PairingStore:
    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def load(self) -> dict[str, Pairing]:
        if not self.path.exists():
            return {}
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            return {k: Pairing(**v) for k, v in raw.items()}
        except (ValueError, TypeError, AttributeError):
            os.replace(self.path, self.path.with_name(self.path.name + ".bak"))
            return {}

    def _write(self, items: dict[str, Pairing]) -> None:
        _write_atomic(self.path, {k: asdict(v) for k, v in items.items()})

    def save(self, p: Pairing) -> None:
        items = self.load()
        items[p.phone_id] = p
        self._write(items)

    def remove(self, phone_id: str) -> None:
        items = self.load()
        if items.pop(phone_id, None) is not None:
            self._write(items)

    def get(self, phone_id: str) -> Pairing | None:
        return self.load().get(phone_id)

    def update_address(self, phone_id: str, host: str, port: int) -> None:
        p = self.get(phone_id)
        if p is not None:
            self.save(replace(p, last_host=host, last_port=port))


def pc_identity(path: Path | None = None) -> tuple[str, str]:
    """(pcId, pcName) ; pcId est créé une fois puis réutilisé."""
    path = Path(path) if path else app_dir() / "pc.json"
    name = platform.node() or "PC"
    try:
        pc_id = json.loads(path.read_text(encoding="utf-8"))["pcId"]
        if isinstance(pc_id, str) and pc_id:
            return pc_id, name
    except (OSError, ValueError, KeyError, TypeError):
        pass
    pc_id = uuid.uuid4().hex
    _write_atomic(path, {"pcId": pc_id})
    return pc_id, name
