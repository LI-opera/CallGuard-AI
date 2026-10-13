"""Bounded local VB-CABLE probe. No phone control, microphone, network or AI."""
import argparse
from array import array
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sys
import time
import wave

FREQUENCIES = (440, 880, 660)
RATE = 48000
CHANNELS = 2


def select_cable(devices, apis, direction):
    expected = 'CABLE Output (VB-Audio Virtual Cable)' if direction == 'input' else 'CABLE Input (VB-Audio Virtual Cable)'
    matches = [i for i, d in enumerate(devices)
               if d['name'] == expected and apis[d['hostapi']]['name'] == 'Windows WASAPI'
               and d['max_' + direction + '_channels'] >= CHANNELS]
    if len(matches) != 1:
        raise RuntimeError(f'Expected exactly one WASAPI {expected}; found {len(matches)}. No default fallback.')
    return matches[0]


def stimulus():
    samples = array('h', [0] * (RATE // 5 * CHANNELS))
    for frequency in FREQUENCIES:
        for n in range(RATE * 2 // 5):
            # Low amplitude, 10ms fades; both channels carry the same stimulus.
            fade = min(1, n / 480, (RATE * 2 // 5 - 1 - n) / 480)
            sample = int(2000 * fade * math.sin(2 * math.pi * frequency * n / RATE))
            samples.extend((sample, sample))
        samples.extend([0] * (RATE // 10 * CHANNELS))
    samples.extend([0] * (RATE // 5 * CHANNELS))
    if sys.byteorder != 'little':
        samples.byteswap()
    return samples.tobytes()


def analyze(pcm):
    data = array('h')
    data.frombytes(pcm)
    if sys.byteorder != 'little':
        data.byteswap()
    mono = data[::CHANNELS]
    window = RATE // 20
    runs = []
    for start in range(0, len(mono) - window + 1, window):
        block = mono[start:start + window]
        energy = sum(x * x for x in block)
        if energy / window < 10000:
            continue
        powers = []
        for frequency in FREQUENCIES:
            coefficient = 2 * math.cos(2 * math.pi * frequency / RATE)
            q1 = q2 = 0.0
            for sample in block:
                q0 = sample + coefficient * q1 - q2
                q2, q1 = q1, q0
            powers.append(max(0, q1*q1 + q2*q2 - coefficient*q1*q2))
        best = max(range(3), key=powers.__getitem__)
        purity = 2 * powers[best] / (window * energy)
        if purity < 0.8:
            continue
        frequency = FREQUENCIES[best]
        if runs and runs[-1]['hz'] == frequency:
            runs[-1]['windows'] += 1
        else:
            runs.append({'hz': frequency, 'windows': 1})
    passed = [r['hz'] for r in runs] == list(FREQUENCIES) and all(r['windows'] >= 4 for r in runs)
    return {'detectedRuns': runs, 'toneSequenceVerified': passed, 'capturedFrames': len(mono)}


def save_wav(path, pcm):
    with wave.open(str(path), 'wb') as output:
        output.setparams((CHANNELS, 2, RATE, 0, 'NONE', 'not compressed'))
        output.writeframes(pcm)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    # Unique directories retain each run's raw data rather than overwriting evidence.
    args.output_dir.mkdir(parents=True, exist_ok=False)
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'scope': 'local_virtual_cable_only',
              'sampleRate': RATE, 'channels': CHANNELS, 'sampleFormat': 'PCM16_LE',
              'phoneAudioVerified': False, 'automaticCallControlVerified': False,
              'localLoopbackPassed': False}
    chunks, statuses = [], []
    try:
        import sounddevice as sd
        devices, apis = sd.query_devices(), sd.query_hostapis()
        source = select_cable(devices, apis, 'input')
        sink = select_cable(devices, apis, 'output')
        report.update(inputDevice=dict(devices[source]), outputDevice=dict(devices[sink]), sounddeviceVersion=sd.__version__)
        sd.check_input_settings(device=source, channels=CHANNELS, dtype='int16', samplerate=RATE)
        sd.check_output_settings(device=sink, channels=CHANNELS, dtype='int16', samplerate=RATE)
        pcm = stimulus()
        save_wav(args.output_dir / 'sent.wav', pcm)

        def receive(data, frames, timing, status):
            if status:
                statuses.append(str(status))
            if sum(map(len, chunks)) + len(data) > RATE * CHANNELS * 2 * 6:
                raise sd.CallbackAbort
            chunks.append(bytes(data))

        with sd.RawInputStream(device=source, samplerate=RATE, channels=CHANNELS, dtype='int16', callback=receive):
            time.sleep(0.25)
            with sd.RawOutputStream(device=sink, samplerate=RATE, channels=CHANNELS, dtype='int16') as playback:
                report['outputUnderflow'] = bool(playback.write(pcm))
            time.sleep(0.3)
        captured = b''.join(chunks)
        save_wav(args.output_dir / 'received.wav', captured)
        report.update(analyze(captured))
        report.update(inputStatuses=statuses, sentSha256=hashlib.sha256(pcm).hexdigest(),
                      receivedSha256=hashlib.sha256(captured).hexdigest())
        report['localLoopbackPassed'] = report['toneSequenceVerified'] and not statuses and not report['outputUnderflow']
    except Exception as error:
        report['error'] = f'{type(error).__name__}: {error}'
    (args.output_dir / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report['localLoopbackPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
