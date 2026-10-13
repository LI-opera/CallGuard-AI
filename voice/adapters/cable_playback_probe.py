"""Play one bounded PCM WAV to the exact VB-CABLE output, never a default device."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import wave
from voice.adapters.cable_loopback_probe import select_cable, RATE, CHANNELS


def load_wav(path):
    with wave.open(str(path), 'rb') as source:
        if (source.getframerate(), source.getnchannels(), source.getsampwidth(), source.getcomptype()) != (RATE, CHANNELS, 2, 'NONE'):
            raise ValueError('Require 48kHz stereo PCM16 WAV')
        frames = source.getnframes()
        if not 0 < frames <= RATE * 15:
            raise ValueError('Require nonempty WAV of at most 15 seconds')
        pcm = source.readframes(frames)
        if len(pcm) != frames * CHANNELS * 2:
            raise ValueError('Truncated WAV')
        return pcm, frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wav', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Reserve a new report before any audio operation; preserve every attempt.
    with args.report.open('x', encoding='utf-8') as output:
        result = {'startedAt': datetime.now(timezone.utc).isoformat(), 'checkOnly': args.check_only,
                  'submittedFrames': 0, 'playbackCompleted': False, 'remoteHeard': 'PENDING_USER_OBSERVATION',
                  'callControlUsed': False, 'recordedAudio': False}
        try:
            import sounddevice as sd
            pcm, frames = load_wav(args.wav)
            devices, apis = sd.query_devices(), sd.query_hostapis()
            device = select_cable(devices, apis, 'output')
            sd.check_output_settings(device=device, channels=CHANNELS, dtype='int16', samplerate=RATE)
            result.update(device=dict(devices[device]), durationSeconds=frames/RATE,
                          pcmSha256=hashlib.sha256(pcm).hexdigest(), preflightPassed=True)
            if not args.check_only:
                with sd.RawOutputStream(device=device, samplerate=RATE, channels=CHANNELS, dtype='int16') as stream:
                    result['outputUnderflow'] = bool(stream.write(pcm))
                    result['submittedFrames'] = frames
                result['playbackCompleted'] = True
        except Exception as error:
            result['error'] = f'{type(error).__name__}: {error}'
        finally:
            result['finishedAt'] = datetime.now(timezone.utc).isoformat()
            json.dump(result, output, ensure_ascii=False, indent=2)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 1 if 'error' in result or result.get('outputUnderflow') else 0


if __name__ == '__main__':
    raise SystemExit(main())
