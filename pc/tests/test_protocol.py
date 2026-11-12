import pytest

from eyes_pc.protocol import (
    PROTOCOL_VERSION,
    ProtocolError,
    answer_msg,
    decode,
    encode,
    hello_msg,
    pair_msg,
    set_msg,
    start_msg,
    stop_msg,
)


def test_roundtrip_start():
    assert decode(encode(start_msg(True, False, "front"))) == {
        "type": "start",
        "video": True,
        "audio": False,
        "camera": "front",
    }


def test_pair_includes_version():
    assert decode(encode(pair_msg("123456", "pc1", "Bureau")))["v"] == 1
    assert PROTOCOL_VERSION == 1


def test_hello_answer_stop_set_roundtrip():
    assert decode(encode(hello_msg("pc1", "tok"))) == {"type": "hello", "pcId": "pc1", "token": "tok", "v": 1}
    assert decode(encode(answer_msg("sdp"))) == {"type": "answer", "sdp": "sdp"}
    assert decode(encode(stop_msg())) == {"type": "stop"}
    assert decode(encode(set_msg(camera="back"))) == {"type": "set", "camera": "back"}


def test_phone_messages_decode():
    assert decode('{"type":"denied","reason":"revoked"}')["reason"] == "revoked"
    assert decode('{"type":"error","code":"busy"}')["code"] == "busy"
    assert decode('{"type":"welcome","phoneId":"p","phoneName":"Tel"}')["phoneName"] == "Tel"
    assert decode('{"type":"paired","token":"t","phoneId":"p","phoneName":"Tel"}')["token"] == "t"
    assert decode('{"type":"offer","sdp":"x"}')["sdp"] == "x"


@pytest.mark.parametrize(
    "raw",
    [
        "pas du json",
        "{}",
        "[]",
        '{"type":"inconnu"}',
        '{"type":"offer"}',
        '{"type":"start","video":"oui","audio":true,"camera":"back"}',
        '{"type":"start","video":true,"audio":true,"camera":"cote"}',
        '{"type":"set"}',
        '{"type":"denied","reason":"autre"}',
        '{"type":"hello","pcId":"p","token":"t","v":true}',
    ],
)
def test_invalid_messages_raise(raw):
    with pytest.raises(ProtocolError):
        decode(raw)


def test_set_msg_requires_a_change():
    with pytest.raises(ProtocolError):
        set_msg()
