# miniVDesk

English | [简体中文](README.md)

miniVDesk is a next-generation Android app container / virtualization engine. It abandons the easily-detected approaches of traditional sandboxes — Java proxies, Binder hooks and ioctl hooks — in favor of a self-developed system-service interception design. Apps run essentially unaware of the container, and mainstream detector apps/SDKs that look for system-service hooking can be passed without extra anti-detection patches. Path redirection uses a stealth-oriented seccomp-BPF design, minimizing exposure to common PLT-hook and inline-hook detection.

## Highlights

### 1. Lightweight

The engine SDK is highly self-contained at the native layer; on the Java side it only needs a set of stub slots and a thin Application class. No SDK API calls are required to bring up your own virtual desktop, and the sample APK in this project is only 1.2 MB.

### 2. High performance

Virtualization is achieved by intercepting and taking over system services — no secondary system or subsystem is created. Memory and CPU overhead stay small, and app launch time is essentially the same as native.

### 3. Convenience

Thanks to the sandbox model, an app's activities are not constrained by the `export` attribute and can be switched freely with simple calls. The engine is also positioned as the technical foundation for on-device AI agents; automation APIs will be gradually added and expanded for RPA use cases.

### 4. Stealth

The architecture is stealthy by nature. On top of that, modifications to files and in-memory data such as maps, status, soinfo and system properties further improve anti-detection. It currently passes environment checkers such as Momo while running mainstream social apps stably.

## Use cases

1. **Data isolation** — 10 virtual devices are preset and more can be added; app data is isolated per virtual device, enabling independent backup and switching of login states.
2. **App testing** — simulate different device models by editing device fingerprints, making it easy to test apps in different environments.
3. **RPA** — removes mobile RPA's dependency on adb commands and greatly simplifies RPA scripting with built-in commands; custom AI-agent development is available when needed.
4. **Remote control** — forward control commands or automation scripts through a relay server to operate apps inside the sandbox remotely.
5. **Reverse engineering** — on non-rooted phones, load frida-gadget.so through the engine for hook analysis or script injection.

## Notes

- The free SDK edition can be used freely; it currently supports Android 10 only and keeps multi-instance (multi-open) as the sole feature.
- For newer Android versions, or for add-ons such as Frida debugging and device-fingerprint editing, contact us on WeChat: `sanxiao922`.
- Follow our WeChat official account for updates: 三言说两语.
