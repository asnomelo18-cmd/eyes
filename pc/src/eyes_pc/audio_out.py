"""Mélange du son des téléphones et sortie sur les haut-parleurs du PC."""

from __future__ import annotations

import threading
from typing import Literal

import av
import numpy as np

SAMPLE_RATE = 48000
MAX_BUFFER_SAMPLES = SAMPLE_RATE // 2  # 500 ms : au-delà on jette le plus ancien


class _PhoneBuffer:
    def __init__(self) -> None:
        self.resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
        self.data = np.zeros(0, dtype=np.float32)


class AudioRouter:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._buffers: dict[str, _PhoneBuffer] = {}
        self._mode: Literal["one", "all"] = "one"
        self._selected: str | None = None

    def set_mode(self, mode: Literal["one", "all"]) -> None:
        self._mode = mode

    @property
    def mode(self) -> str:
        return self._mode

    def select(self, phone_id: str | None) -> None:
        self._selected = phone_id

    @property
    def selected(self) -> str | None:
        return self._selected

    def push(self, phone_id: str, frame: av.AudioFrame) -> None:
        with self._lock:
            buf = self._buffers.setdefault(phone_id, _PhoneBuffer())
        chunks = [f.to_ndarray().reshape(-1) for f in buf.resampler.resample(frame)]
        if not chunks:
            return
        samples = np.concatenate(chunks).astype(np.float32) / 32768.0
        with self._lock:
            buf.data = np.concatenate([buf.data, samples])[-MAX_BUFFER_SAMPLES:]

    def remove(self, phone_id: str) -> None:
        with self._lock:
            self._buffers.pop(phone_id, None)
        if self._selected == phone_id:
            self._selected = None

    def buffered(self, phone_id: str) -> int:
        with self._lock:
            buf = self._buffers.get(phone_id)
            return 0 if buf is None else len(buf.data)

    def mix(self, n_samples: int) -> np.ndarray:
        out = np.zeros(n_samples, dtype=np.float32)
        with self._lock:
            for phone_id, buf in self._buffers.items():
                chunk, buf.data = buf.data[:n_samples], buf.data[n_samples:]
                if self._mode == "all" or phone_id == self._selected:
                    out[: len(chunk)] += chunk
        return np.clip(out, -1.0, 1.0)


class AudioOutput:
    """Flux sounddevice qui lit le mélange en continu."""

    def __init__(self, router: AudioRouter) -> None:
        self._router = router
        self._stream = None

    def start(self) -> None:
        import sounddevice as sd

        def callback(outdata, frames, _time, _status) -> None:
            outdata[:, 0] = self._router.mix(frames)

        self._stream = sd.OutputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                       blocksize=960, latency="low", callback=callback)
        self._stream.start()

    def stop(self) -> None:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None
