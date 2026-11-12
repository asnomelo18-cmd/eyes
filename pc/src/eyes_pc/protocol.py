"""Messages JSON échangés entre le PC et le téléphone (protocole v1)."""

from __future__ import annotations

import json
from typing import Any

PROTOCOL_VERSION = 1

CAMERAS = frozenset({"back", "front"})
DENIED_REASONS = frozenset({"unknown", "revoked", "version", "bad_code", "code_expired", "not_pairing"})
ERROR_CODES = frozenset({"busy", "camera_busy", "permission_missing", "bad_message", "media_error"})


class ProtocolError(Exception):
    """Message invalide."""


# type -> {champ: type python ou ensemble de valeurs autorisées}
_REQUIRED: dict[str, dict[str, Any]] = {
    "pair": {"code": str, "pcId": str, "pcName": str, "v": int},
    "paired": {"token": str, "phoneId": str, "phoneName": str},
    "hello": {"pcId": str, "token": str, "v": int},
    "welcome": {"phoneId": str, "phoneName": str},
    "denied": {"reason": DENIED_REASONS},
    "start": {"video": bool, "audio": bool, "camera": CAMERAS},
    "offer": {"sdp": str},
    "answer": {"sdp": str},
    "ice": {},
    "set": {},
    "stop": {},
    "error": {"code": ERROR_CODES},
}
_SET_OPTIONAL: dict[str, Any] = {"video": bool, "audio": bool, "camera": CAMERAS}


def _check(value: Any, rule: Any) -> bool:
    if isinstance(rule, frozenset):
        return isinstance(value, str) and value in rule
    if rule is int:
        return isinstance(value, int) and not isinstance(value, bool)
    return isinstance(value, rule)


def validate(msg: Any) -> dict:
    if not isinstance(msg, dict):
        raise ProtocolError("le message n'est pas un objet")
    kind = msg.get("type")
    if kind not in _REQUIRED:
        raise ProtocolError(f"type inconnu : {kind!r}")
    for field, rule in _REQUIRED[kind].items():
        if field not in msg or not _check(msg[field], rule):
            raise ProtocolError(f"champ {field!r} manquant ou invalide dans {kind!r}")
    if kind == "set":
        present = [f for f in _SET_OPTIONAL if f in msg]
        if not present:
            raise ProtocolError("'set' sans changement")
        for field in present:
            if not _check(msg[field], _SET_OPTIONAL[field]):
                raise ProtocolError(f"champ {field!r} invalide dans 'set'")
    return msg


def encode(msg: dict) -> str:
    return json.dumps(validate(msg), separators=(",", ":"))


def decode(raw: str) -> dict:
    try:
        msg = json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ProtocolError("JSON invalide") from exc
    return validate(msg)


def pair_msg(code: str, pc_id: str, pc_name: str) -> dict:
    return {"type": "pair", "code": code, "pcId": pc_id, "pcName": pc_name, "v": PROTOCOL_VERSION}


def hello_msg(pc_id: str, token: str) -> dict:
    return {"type": "hello", "pcId": pc_id, "token": token, "v": PROTOCOL_VERSION}


def start_msg(video: bool, audio: bool, camera: str) -> dict:
    return validate({"type": "start", "video": video, "audio": audio, "camera": camera})


def set_msg(**changes: Any) -> dict:
    return validate({"type": "set", **changes})


def answer_msg(sdp: str) -> dict:
    return {"type": "answer", "sdp": sdp}


def stop_msg() -> dict:
    return {"type": "stop"}
