import asyncio
import uuid
import wave
from pathlib import Path
import pytest
from voice.contracts import AudioFormat, AudioFrame, VoiceSession
from voice.session import run_session
from voice.processors.wav_greeting import WavGreeting

class MemoryChannel:
    """Test double; deliberately never registered as a production adapter."""
    def __init__(self, frames=(), answer_error=False):
        self.frames=frames; self.sent=[]; self.events=[]; self.answer_error=answer_error
    async def answer(self, session):
        self.events.append("answer")
        if self.answer_error: raise OSError("answer failed")
    async def incoming(self):
        for frame in self.frames: yield frame
    async def send(self, frame): self.sent.append(frame)
    async def drain(self): self.events.append("drain")
    async def hangup(self): self.events.append("hangup")
    async def aclose(self): self.events.append("close")

class Echo:
    async def run(self, session, incoming):
        async for frame in incoming: yield frame

class Waiting:
    async def run(self, session, incoming):
        await asyncio.Event().wait()
        yield AudioFrame(0,b"")

def session(rate=8000): return VoiceSession(str(uuid.uuid4()),AudioFormat(rate))

@pytest.mark.asyncio
async def test_frames_preserved_and_call_released():
    channel=MemoryChannel([AudioFrame(0,b"x"*320),AudioFrame(1,b"y"*320)])
    result=await run_session(channel,Echo(),session())
    assert result.received_frames==result.submitted_frames==2
    assert channel.sent==channel.frames
    assert channel.events==["answer","drain","hangup","close"]

@pytest.mark.asyncio
@pytest.mark.parametrize("frame",[AudioFrame(1,b"x"*320),AudioFrame(0,b"x")])
async def test_malformed_input_fails_and_cleans_up(frame):
    channel=MemoryChannel([frame])
    with pytest.raises(ValueError): await run_session(channel,Echo(),session())
    assert channel.sent==[] and channel.events[-2:]==["hangup","close"]

@pytest.mark.asyncio
async def test_deadline_releases_channel():
    channel=MemoryChannel()
    with pytest.raises(TimeoutError): await run_session(channel,Waiting(),session(),timeout_seconds=.02)
    assert channel.events[-2:]==["hangup","close"]

@pytest.mark.asyncio
async def test_cancellation_releases_channel():
    channel=MemoryChannel()
    task=asyncio.create_task(run_session(channel,Waiting(),session()))
    await asyncio.sleep(.01);task.cancel()
    with pytest.raises(asyncio.CancelledError): await task
    assert channel.events[-2:]==["hangup","close"]

@pytest.mark.asyncio
async def test_failed_answer_still_releases_resources():
    channel=MemoryChannel(answer_error=True)
    with pytest.raises(OSError): await run_session(channel,Echo(),session())
    assert channel.events==["answer","hangup","close"]

@pytest.mark.asyncio
async def test_cleanup_close_attempted_when_hangup_fails():
    class BrokenHangup(MemoryChannel):
        async def hangup(self):
            self.events.append("hangup");raise OSError("hangup failed")
    channel=BrokenHangup()
    with pytest.raises(OSError): await run_session(channel,Echo(),session())
    assert channel.events[-1]=="close"

@pytest.mark.asyncio
async def test_wav_probe_preserves_samples_and_pads_last_frame(tmp_path):
    path=tmp_path/'greeting.wav'
    pcm=b"\x01\x02"*170
    with wave.open(str(path),'wb') as wav:
        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(8000);wav.writeframes(pcm)
    channel=MemoryChannel()
    result=await run_session(channel,WavGreeting(path),session())
    assert result.received_frames==0 and result.submitted_frames==2
    assert b''.join(f.pcm for f in channel.sent)==pcm+b'\0'*300
    with pytest.raises(ValueError): await run_session(MemoryChannel(),WavGreeting(path),session(16000))

@pytest.mark.parametrize('rate',[0,44100,48000])
def test_unsupported_format_rejected(rate):
    with pytest.raises(ValueError): AudioFormat(rate)


@pytest.mark.asyncio
async def test_stalled_playback_drain_is_bounded():
    class StalledDrain(MemoryChannel):
        async def drain(self):
            self.events.append("drain")
            await asyncio.Event().wait()
    channel=StalledDrain()
    with pytest.raises(TimeoutError):
        await run_session(channel,Echo(),session(),timeout_seconds=.02)
    assert channel.events==["answer","drain","hangup","close"]
