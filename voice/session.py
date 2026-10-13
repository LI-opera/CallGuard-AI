"""Executable bounded session lifecycle; no transport or AI-provider dependency."""
import asyncio
from contextlib import aclosing
from dataclasses import dataclass
from .contracts import AudioFrame, AudioProcessor, CallChannel, VoiceSession

@dataclass(frozen=True)
class SessionResult:
    session_id: str
    received_frames: int
    submitted_frames: int
    # Submission does not prove the remote human heard sound.

async def run_session(channel: CallChannel, processor: AudioProcessor,
                      session: VoiceSession, timeout_seconds: float = 45,
                      cleanup_seconds: float = 2) -> SessionResult:
    if not 0 < timeout_seconds <= 120 or not 0 < cleanup_seconds <= 10:
        raise ValueError("Invalid session bounds")
    received = submitted = 0

    async def checked_input():
        nonlocal received
        async with aclosing(channel.incoming()) as source:
            async for frame in source:
                frame.validate(session.format, received)
                received += 1
                yield frame

    try:
        async with asyncio.timeout(timeout_seconds):
            await channel.answer(session)
            async with aclosing(checked_input()) as source:
                async with aclosing(processor.run(session, source)) as response:
                    async for frame in response:
                        frame.validate(session.format, submitted)
                        await channel.send(frame)
                        submitted += 1
            await channel.drain()
        return SessionResult(session.session_id, received, submitted)
    finally:
        # Try both even if hangup fails; cleanup failures remain visible to caller.
        try:
            async with asyncio.timeout(cleanup_seconds):
                await channel.hangup()
        finally:
            async with asyncio.timeout(cleanup_seconds):
                await channel.aclose()
