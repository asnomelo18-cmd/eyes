import av
import numpy as np

from eyes_pc.audio_out import MAX_BUFFER_SAMPLES, PRIME_MS, SAMPLE_RATE, AudioRouter


def frame(value: float, n: int = 960, rate: int = 48000, layout: str = "mono") -> av.AudioFrame:
    channels = 1 if layout == "mono" else 2
    data = np.full((1, n * channels), int(value * 32767), dtype=np.int16)
    f = av.AudioFrame.from_ndarray(data, format="s16", layout=layout)
    f.sample_rate = rate
    return f


def test_mode_one_only_selected_heard():
    r = AudioRouter()
    push_ms(r, "a", PRIME_MS, 0.5)
    push_ms(r, "b", PRIME_MS, -0.25)
    r.select("b")
    out = r.mix(960)
    assert out.dtype == np.float32 and out.shape == (960,)
    assert np.allclose(out[100:], -0.25, atol=0.01)


def test_mode_one_without_selection_is_silent():
    r = AudioRouter()
    r.push("a", frame(0.5))
    assert np.allclose(r.mix(960), 0)


def test_mode_all_mixes_and_clips_to_unit_range():
    r = AudioRouter()
    r.set_mode("all")
    push_ms(r, "a", PRIME_MS, 0.8)
    push_ms(r, "b", PRIME_MS, 0.8)
    out = r.mix(960)
    assert out.max() <= 1.0 and np.allclose(out[100:], 1.0, atol=0.01)


def test_no_data_gives_silence():
    assert np.allclose(AudioRouter().mix(480), 0)


def test_stereo_input_is_downmixed():
    r = AudioRouter()
    r.select("a")
    for _ in range(PRIME_MS // 20):
        r.push("a", frame(0.5, layout="stereo"))
    assert np.allclose(r.mix(960)[100:], 0.5, atol=0.01)


def test_buffer_capped_at_500ms():
    assert MAX_BUFFER_SAMPLES == SAMPLE_RATE // 2
    r = AudioRouter()
    r.select("a")
    for _ in range(100):  # 2 s de son
        r.push("a", frame(0.5))
    assert r.buffered("a") <= MAX_BUFFER_SAMPLES


def test_unselected_buffers_are_drained():
    r = AudioRouter()
    r.select("a")
    r.push("b", frame(0.5))
    r.mix(960)
    r.select("b")
    assert np.allclose(r.mix(960), 0)  # le vieux son de b n'est pas rejoué


def test_remove_phone():
    r = AudioRouter()
    r.push("a", frame(0.5))
    r.remove("a")
    assert r.buffered("a") == 0


def push_ms(r: AudioRouter, phone: str, ms: int, value: float = 0.5) -> None:
    for _ in range(ms // 20):
        r.push(phone, frame(value))


def test_playback_waits_for_jitter_reserve():
    r = AudioRouter()
    r.select("a")
    r.push("a", frame(0.5))  # 20 ms : pas assez d'avance, on attend
    assert np.allclose(r.mix(960), 0)
    push_ms(r, "a", PRIME_MS)
    assert np.allclose(r.mix(960)[100:], 0.5, atol=0.01)


def test_underrun_waits_for_reserve_again():
    r = AudioRouter()
    r.select("a")
    push_ms(r, "a", PRIME_MS)
    for _ in range(PRIME_MS // 20):
        r.mix(960)
    r.mix(960)  # trou : plus rien
    r.push("a", frame(0.5))  # un seul paquet en retard ne relance pas le son
    assert np.allclose(r.mix(960), 0)


def test_latency_does_not_grow_past_high_water():
    r = AudioRouter()
    r.select("a")
    push_ms(r, "a", 400)  # rafale après un trou réseau
    r.mix(960)
    assert r.buffered("a") <= SAMPLE_RATE * PRIME_MS // 1000
