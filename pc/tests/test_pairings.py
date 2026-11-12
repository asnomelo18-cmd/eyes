from eyes_pc.pairings import Pairing, PairingStore, pc_identity


def _p(phone_id="ph1"):
    return Pairing(phone_id, "Tel de Marie", "jeton", "ab" * 32, None, None)


def test_save_then_load_roundtrip(tmp_path):
    store = PairingStore(tmp_path / "pairings.json")
    store.save(_p())
    assert PairingStore(tmp_path / "pairings.json").load() == {"ph1": _p()}


def test_remove(tmp_path):
    store = PairingStore(tmp_path / "pairings.json")
    store.save(_p("a"))
    store.save(_p("b"))
    store.remove("a")
    assert list(store.load()) == ["b"]
    assert store.get("a") is None


def test_missing_file_gives_empty(tmp_path):
    assert PairingStore(tmp_path / "absent" / "pairings.json").load() == {}


def test_corrupt_file_gives_empty_and_is_backed_up(tmp_path):
    path = tmp_path / "pairings.json"
    path.write_text("{ cassé", encoding="utf-8")
    assert PairingStore(path).load() == {}
    assert (tmp_path / "pairings.json.bak").read_text(encoding="utf-8") == "{ cassé"


def test_update_address(tmp_path):
    store = PairingStore(tmp_path / "pairings.json")
    store.save(_p())
    store.update_address("ph1", "192.168.1.20", 40123)
    p = store.get("ph1")
    assert (p.last_host, p.last_port) == ("192.168.1.20", 40123)


def test_pc_identity_is_stable(tmp_path):
    first = pc_identity(tmp_path / "pc.json")
    second = pc_identity(tmp_path / "pc.json")
    assert first == second
    assert len(first[0]) >= 32 and first[1]
