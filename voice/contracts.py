"""No Bluetooth, SIP, Android or model-provider imports belong in this module."""
from dataclasses import dataclass, field
from collections.abc import AsyncIterator, AsyncGenerator
from typing import Protocol
from uuid import UUID

@dataclass(frozen=True)
class AudioFormat:
    sample_rate: int
    channels: int = 1
    sample_width: int = 2
    frame_ms: int = 20

    def __post_init__(self):
        if self.sample_rate not in (8000, 16000) or (self.channels, self.sample_width, self.frame_ms) != (1, 2, 20):
            raise ValueError("Require mono signed PCM16 little-endian, 8/16kHz, 20ms")

    @property
    def frame_bytes(self) -> int:
        return self.sample_rate * self.sample_width * self.frame_ms // 1000

@dataclass(frozen=True)
class AudioFrame:
    sequence: int
    pcm: bytes = field(repr=False)

    def validate(self, format: AudioFormat, expected_sequence: int):
        if type(self.sequence) is not int or self.sequence != expected_sequence:
            raise ValueError("Audio frame sequence mismatch")
        if not isinstance(self.pcm, bytes) or len(self.pcm) != format.frame_bytes:
            raise ValueError("Audio frame size mismatch")

@dataclass(frozen=True)
class VoiceSession:
    session_id: str
    format: AudioFormat

    def __post_init__(self):
        if str(UUID(self.session_id)) != self.session_id:
            raise ValueError("Canonical session UUID required")

class CallChannel(Protocol):
    """Owns hardware/transport, codecs, real-time pacing and call-control.

    incoming ends on remote hangup. send must be bounded by backpressure,
    not an unbounded queue. hangup/aclose must be safe after partial answer.
    This is an interface, not an implemented Bluetooth adapter.
    """
    async def answer(self, session: VoiceSession) -> None: ...
    def incoming(self) -> AsyncGenerator[AudioFrame, None]: ...
    async def send(self, frame: AudioFrame) -> None: ...
    async def drain(self) -> None:
        """Wait until submitted audio has left the playback queue, not just been enqueued."""
        ...
    async def hangup(self) -> None: ...
    async def aclose(self) -> None: ...

class AudioProcessor(Protocol):
    """Consumes canonical audio and yields speech, independent of the channel.

    Provider factories, ASR/VAD/TTS and dialogue live behind this boundary.
    This first probe does not implement those model services.
    """
    def run(self, session: VoiceSession, incoming: AsyncIterator[AudioFrame]) -> AsyncGenerator[AudioFrame, None]: ...
