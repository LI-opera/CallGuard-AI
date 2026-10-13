#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include <propsys.h>
#include <wrl/client.h>
#include <filesystem>
#include <fstream>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <vector>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

using Microsoft::WRL::ComPtr;

static void check(HRESULT result, const char* operation)
{
    if (FAILED(result)) {
        std::ostringstream message;
        message << operation << " failed: HRESULT 0x" << std::hex
                << static_cast<unsigned long>(result);
        throw std::runtime_error(message.str());
    }
}

static std::wstring friendlyName(IMMDevice* device)
{
    ComPtr<IPropertyStore> store;
    check(device->OpenPropertyStore(STGM_READ, store.GetAddressOf()), "OpenPropertyStore");
    const PROPERTYKEY key = {
        {0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}},
        14
    };
    PROPVARIANT value;
    PropVariantInit(&value);
    check(store->GetValue(key, &value), "GetValue FriendlyName");
    std::wstring name = value.vt == VT_LPWSTR && value.pwszVal != nullptr ?
        std::wstring(value.pwszVal) : L"unknown";
    PropVariantClear(&value);
    return name;
}

static std::string utf8Json(const std::wstring& value)
{
    if (value.empty()) return "";
    const int bytes = WideCharToMultiByte(CP_UTF8, 0, value.c_str(),
                                           static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(bytes, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()),
                        &result[0], bytes, nullptr, nullptr);
    std::string escaped;
    for (unsigned char ch : result) {
        if (ch == '"' || ch == '\\') escaped += '\\';
        if (ch >= 0x20) escaped += static_cast<char>(ch);
    }
    return escaped;
}
struct LevelBucket
{
    double sumSquares = 0.0;
    double peak = 0.0;
    unsigned long long samples = 0;
};

static double dbfs(double value)
{
    return 20.0 * std::log10(value > 0.000001 ? value : 0.000001);
}

static int sampleKind(const WAVEFORMATEX* format)
{
    const GUID pcm = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
    const GUID floating = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
    if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT && format->wBitsPerSample == 32) return 1;
    if (format->wFormatTag == WAVE_FORMAT_PCM && format->wBitsPerSample == 16) return 2;
    if (format->wFormatTag == WAVE_FORMAT_PCM && format->wBitsPerSample == 32) return 3;
    if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE && format->cbSize >= 22) {
        const WAVEFORMATEXTENSIBLE* extended = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(format);
        if (IsEqualGUID(extended->SubFormat, floating) && format->wBitsPerSample == 32) return 1;
        if (IsEqualGUID(extended->SubFormat, pcm) && format->wBitsPerSample == 16) return 2;
        if (IsEqualGUID(extended->SubFormat, pcm) && format->wBitsPerSample == 32) return 3;
    }
    return 0;
}

static void accumulate(LevelBucket& bucket, const BYTE* data, unsigned int frames,
                       unsigned int channels, int kind, bool silent)
{
    const size_t count = static_cast<size_t>(frames) * channels;
    bucket.samples += count;
    if (silent || kind == 0) return;
    const size_t width = kind == 2 ? 2 : 4;
    for (size_t i = 0; i < count; ++i) {
        double value = 0.0;
        if (kind == 1) {
            float sample;
            std::memcpy(&sample, data + i * width, width);
            value = static_cast<double>(sample);
        } else if (kind == 2) {
            int16_t sample;
            std::memcpy(&sample, data + i * width, width);
            value = static_cast<double>(sample) / 32768.0;
        } else {
            int32_t sample;
            std::memcpy(&sample, data + i * width, width);
            value = static_cast<double>(sample) / 2147483648.0;
        }
        if (!std::isfinite(value)) continue;
        bucket.sumSquares += value * value;
        const double absolute = std::abs(value);
        if (absolute > bucket.peak) bucket.peak = absolute;
    }
}
struct ComApartment
{
    ComApartment() { check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "CoInitializeEx"); }
    ~ComApartment() { CoUninitialize(); }
};

struct MixFormat
{
    WAVEFORMATEX* value = nullptr;
    ~MixFormat() { if (value != nullptr) CoTaskMemFree(value); }
};

int main(int argc, char* argv[])
{
    if ((argc != 4 && argc != 6) || std::string(argv[1]) != "--output" ||
        (argc == 6 && std::string(argv[4]) != "--role")) {
        std::cerr << "Usage: WasapiLoopbackProbe.exe --output <new.json> <1..180 seconds> [--role console|multimedia|communications]\n";
        return 2;
    }

    try {
        const std::filesystem::path output = std::filesystem::absolute(argv[2]);
        const int seconds = std::stoi(argv[3]);
        if (seconds < 1 || seconds > 180) {
            throw std::invalid_argument("Capture duration must be 1..180 seconds.");
        }
        if (std::filesystem::exists(output)) {
            throw std::runtime_error("Refusing to overwrite existing evidence.");
        }

        ERole selectedRole = eCommunications;
        std::string roleName = "communications";
        if (argc == 6) {
            roleName = argv[5];
            if (roleName == "console") selectedRole = eConsole;
            else if (roleName == "multimedia") selectedRole = eMultimedia;
            else if (roleName == "communications") selectedRole = eCommunications;
            else throw std::invalid_argument("Unknown render role.");
        }
        ComApartment apartment;
        ComPtr<IMMDeviceEnumerator> enumerator;
        check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                               IID_PPV_ARGS(enumerator.GetAddressOf())),
              "Create MMDeviceEnumerator");
        ComPtr<IMMDevice> endpoint;
        check(enumerator->GetDefaultAudioEndpoint(eRender, selectedRole, endpoint.GetAddressOf()),
              "GetDefaultAudioEndpoint selected role");
        LPWSTR selectedIdRaw = nullptr;
        check(endpoint->GetId(&selectedIdRaw), "GetId selected endpoint");
        const std::wstring selectedId(selectedIdRaw);
        CoTaskMemFree(selectedIdRaw);
        auto sameDefaultRole = [&](ERole role) -> bool {
            ComPtr<IMMDevice> other;
            check(enumerator->GetDefaultAudioEndpoint(eRender, role, other.GetAddressOf()),
                  "GetDefaultAudioEndpoint role");
            LPWSTR idRaw = nullptr;
            check(other->GetId(&idRaw), "GetId role");
            const bool same = selectedId == idRaw;
            CoTaskMemFree(idRaw);
            return same;
        };
        const std::wstring selectedName = friendlyName(endpoint.Get());
        ComPtr<IMMDevice> communicationEndpoint;
        check(enumerator->GetDefaultAudioEndpoint(eRender, eCommunications,
                                                   communicationEndpoint.GetAddressOf()),
              "GetDefaultAudioEndpoint eCommunications");
        const std::wstring communicationName = friendlyName(communicationEndpoint.Get());
        ComPtr<IMMDevice> consoleEndpoint;
        check(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, consoleEndpoint.GetAddressOf()),
              "GetDefaultAudioEndpoint eConsole");
        const std::wstring consoleName = friendlyName(consoleEndpoint.Get());
        ComPtr<IMMDevice> multimediaEndpoint;
        check(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, multimediaEndpoint.GetAddressOf()),
              "GetDefaultAudioEndpoint eMultimedia");
        const std::wstring multimediaName = friendlyName(multimediaEndpoint.Get());
        const bool sameConsole = sameDefaultRole(eConsole);
        const bool sameMultimedia = sameDefaultRole(eMultimedia);
        ComPtr<IAudioClient> audio;
        check(endpoint->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                 reinterpret_cast<void**>(audio.GetAddressOf())),
              "Activate IAudioClient");
        MixFormat mix;
        check(audio->GetMixFormat(&mix.value), "GetMixFormat");
        check(audio->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                10000000, 0, mix.value, nullptr),
              "Initialize loopback");
        ComPtr<IAudioCaptureClient> capture;
        check(audio->GetService(IID_PPV_ARGS(capture.GetAddressOf())),
              "GetService IAudioCaptureClient");

        const int kind = sampleKind(mix.value);
        std::vector<LevelBucket> levels(static_cast<size_t>(seconds));
        unsigned long long totalFrames = 0;
        unsigned long long nonSilentFrames = 0;
        unsigned long long packets = 0;
        check(audio->Start(), "Start");
        const ULONGLONG startedAt = GetTickCount64();
        const ULONGLONG stopAt = startedAt + static_cast<ULONGLONG>(seconds) * 1000;
        try {
            while (GetTickCount64() < stopAt) {
                Sleep(20);
                UINT32 packetFrames = 0;
                check(capture->GetNextPacketSize(&packetFrames), "GetNextPacketSize");
                while (packetFrames != 0) {
                    BYTE* data = nullptr;
                    UINT32 frames = 0;
                    DWORD flags = 0;
                    check(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr),
                          "GetBuffer");
                    const ULONGLONG elapsed = GetTickCount64() - startedAt;
                    size_t bucketIndex = static_cast<size_t>(elapsed / 1000);
                    if (bucketIndex >= levels.size()) bucketIndex = levels.size() - 1;
                    accumulate(levels[bucketIndex], data, frames, mix.value->nChannels,
                               kind, (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0);                    totalFrames += frames;
                    if ((flags & AUDCLNT_BUFFERFLAGS_SILENT) == 0) {
                        nonSilentFrames += frames;
                    }
                    ++packets;
                    check(capture->ReleaseBuffer(frames), "ReleaseBuffer");
                    check(capture->GetNextPacketSize(&packetFrames), "GetNextPacketSize");
                }
            }
        } catch (...) {
            audio->Stop();
            throw;
        }
        check(audio->Stop(), "Stop");

        std::ostringstream json;
        json << "{\"schema\":\"callguard.wasapi_loopback_probe.v1\","
             << "\"scope\":\"selected_render_endpoint_loopback\","
             << "\"mode\":\"read_only_no_audio_saved\","
             << "\"sample_rate\":" << mix.value->nSamplesPerSec << ","
             << "\"channels\":" << mix.value->nChannels << ","
             << "\"bits_per_sample\":" << mix.value->wBitsPerSample << ","
             << "\"format_tag\":" << mix.value->wFormatTag << ","
             << "\"selected_role\":\"" << roleName << "\","
             << "\"selected_endpoint\":\"" << utf8Json(selectedName) << "\","
             << "\"communication_endpoint\":\"" << utf8Json(communicationName) << "\","
             << "\"console_endpoint\":\"" << utf8Json(consoleName) << "\","
             << "\"multimedia_endpoint\":\"" << utf8Json(multimediaName) << "\","
             << "\"same_as_default_console\":" << (sameConsole ? "true" : "false") << ","
             << "\"same_as_default_multimedia\":" << (sameMultimedia ? "true" : "false") << ","
             << "\"duration_seconds\":" << seconds << ","
             << "\"packets\":" << packets << ","
             << "\"total_frames\":" << totalFrames << ","
             << "\"non_silent_frames\":" << nonSilentFrames << ","
             << "\"level_format_supported\":" << (kind != 0 ? "true" : "false") << ","
             << "\"levels\":[";
        for (size_t i = 0; i < levels.size(); ++i) {
            if (i != 0) json << ",";
            const LevelBucket& bucket = levels[i];
            const double rms = bucket.samples == 0 ? 0.0 :
                std::sqrt(bucket.sumSquares / static_cast<double>(bucket.samples));
            json << "{\"second\":" << i
                 << ",\"samples\":" << bucket.samples
                 << ",\"rms_dbfs\":" << std::fixed << std::setprecision(2) << dbfs(rms)
                 << ",\"peak_dbfs\":" << std::fixed << std::setprecision(2) << dbfs(bucket.peak)
                 << "}";
        }
        json << "],"             << "\"phone_link_audio_verified\":false,"
             << "\"caller_speech_verified\":false}";
        std::filesystem::create_directories(output.parent_path());
        std::ofstream file(output, std::ios::binary);
        if (!file) throw std::runtime_error("Cannot create output report.");
        file << json.str() << '\n';
        file.close();
        std::cout << json.str() << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
