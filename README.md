# Fossistant

An on-device Android assistant. Everything runs on the phone: Gemma 4 E2B (LiteRT-LM, with Multi-Token
Prediction) understands what you say in any of its languages and calls the phone's tools itself, and Whisper
turns speech into text. No account and no cloud model. The internet is only used for things like web search,
weather and the one-time model download.

## What it can do

- Voice and text chat, with a wake word and hands-free voice mode
- Phone actions through tools: alarms and timers, calls and texts (after you confirm), calendar, notes,
  navigation, smart home (Home Assistant), weather and web search
- Media control for whatever app is playing: play, pause, skip, seek, shuffle, repeat, speed, like
- Phone settings: volumes, ringer, brightness, auto-rotate, screen timeout, flashlight, Do Not Disturb.
  Wi-Fi, Bluetooth, mobile data, airplane mode and location open their quick panel, since Android only lets
  system apps switch those
- Phone actions: lock screen, screenshot, notifications, quick settings, camera, alarms, battery level
  (some need Fossistant's accessibility service or "Modify system settings", which the app asks for)
- What your installed apps offer: Fossistant reads every app's shortcuts and App Actions and gives Gemma
  the whole list, so it can run them directly
- On-device image generation (DreamShaper 8 LCM through stable-diffusion.cpp, downloaded when first used)
- Voluntary crash reports: after a crash, the app asks whether to email the report. Nothing is sent
  without that.

## Building

Requirements: JDK 21, the Android SDK (platform 35) and an arm64 phone with 8 GB of RAM or more.

```bash
./gradlew assembleSlimDebug
```

The **slim** build has no models inside. On first launch, the setup downloads Gemma 4 E2B (2.6 GB, checked
against a pinned SHA-256) and Whisper into the app's private storage.

Optional build properties (in `gradle.properties` or as `-P`):

- `crashReportEmail=you@example.com`: the address crash reports are emailed to. Without it, the app
  offers the share sheet.
- `gemmaModelUrl=https://...`: a mirror for the Gemma download. It is still checked against the pinned hash.

### Native libraries

The prebuilt JNI libraries are in `app/src/main/jniLibs`. To rebuild them, fetch the exact native sources
used (llama.cpp with Fossistant's patch, whisper.cpp, stable-diffusion.cpp, Vulkan headers):

```bash
./scripts/fetch-third-party.sh
```

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Models and third-party libraries keep their own licences
(Gemma Terms of Use, MIT for llama.cpp, whisper.cpp and stable-diffusion.cpp, and others).

## Warning

I vibecoded this shit in antigravity and claude code from my spare usage of my sub. I know basic coding but this is way out of my league and for all intents and purposes I am not responsible for whatever you do with it and whatever it does or doesnt do. I test each release few hours before releasing to you all so that I can make sure no crashes happen and the app works but I ai coded this shit so expect few bugs to slip in once in a blue moon that I missed.


To any dev who complaints about me using ai to make this app, especially if you are a stereotypical arogant foss dev here is my response : GO FUCK YOURSELF, Yall were telling anyone who had a valid issue with your app to "just fork it if you don't like it" and "If you have issues make your own app". Well fuckers, I did so stop bitching about it and get over yourself.
