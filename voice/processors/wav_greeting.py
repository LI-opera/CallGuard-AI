"""Fixed greeting probe, not speech synthesis or an AI voice agent."""
import wave
from pathlib import Path
from ..contracts import AudioFrame, VoiceSession

class WavGreeting:
    def __init__(self, path: Path):
        # Bounded local fixture read, performed before answering the call.
        if path.stat().st_size > 4_000_000:
            raise ValueError("Greeting file too large")
        with wave.open(str(path), "rb") as wav:
            self.rate = wav.getframerate()
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getcomptype() != "NONE":
                raise ValueError("Require uncompressed mono PCM16 WAV")
            if self.rate not in (8000, 16000) or not 0 < wav.getnframes() <= self.rate * 30:
                raise ValueError("Require 8/16kHz WAV lasting at most 30 seconds")
            self.pcm = wav.readframes(wav.getnframes())
            if len(self.pcm) != wav.getnframes() * 2:
                raise ValueError("Truncated WAV")

    async def run(self, session: VoiceSession, incoming):
        if session.format.sample_rate != self.rate:
            raise ValueError("Resample explicitly before using this greeting")
        size = session.format.frame_bytes
        for sequence, offset in enumerate(range(0, len(self.pcm), size)):
            yield AudioFrame(sequence, self.pcm[offset:offset + size].ljust(size, b"\0"))
        # This output-only probe ends after submission. It is not a duplex/ASR test.
