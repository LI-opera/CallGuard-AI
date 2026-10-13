from voice.adapters.cable_loopback_probe import analyze, select_cable, stimulus
import pytest


def test_tone_sequence_is_required_not_just_nonzero_audio():
    pcm = stimulus()
    assert analyze(pcm)['toneSequenceVerified']
    assert not analyze(bytes(len(pcm)))['toneSequenceVerified']
    assert not analyze(b'\xff\x07' * (len(pcm)//2))['toneSequenceVerified']
    assert not analyze(pcm[:48000])['toneSequenceVerified']


def test_selection_refuses_physical_device_and_duplicate_cables():
    apis = [{'name': 'Windows WASAPI'}]
    cable = {'name': 'CABLE Input (VB-Audio Virtual Cable)', 'hostapi': 0, 'max_output_channels': 2}
    assert select_cable([cable], apis, 'output') == 0
    with pytest.raises(RuntimeError):
        select_cable([dict(cable, name='Speakers')], apis, 'output')
    with pytest.raises(RuntimeError):
        select_cable([cable, cable], apis, 'output')
