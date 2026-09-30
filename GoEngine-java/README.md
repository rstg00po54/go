# Badukai - Minimalist Go/Baduk Android App

A minimalist, single-player Go (Baduk/Weiqi) Android app powered by KataGo, one of the strongest Go AI engines.

## Features

- Clean, minimal UI with flat design
- Play against KataGo AI at three difficulty levels
- Multiple board sizes: 9×9, 13×13, 19×19
- Choose to play as Black or White
- Undo, Pass, Resign controls

## AI Difficulty Levels

| Level | Description | Network |
|-------|-------------|---------|
| **Human** | Approachable opponent, fast responses | 10-block (b10c128) |
| **Superhuman** | Very strong AI, balanced performance | 18-block (b18c384nbt) |
| **Godlike** | Ultimate strength, may be slower | 28-block (b28c512nbt) |

## Technical Details

- Built with Kotlin and Jetpack Compose
- Native KataGo v1.16.0 engine
- GTP (Go Text Protocol) communication
- KataGo neural network models (.bin format)

## Building

### 1. Download Neural Network Models

The model files are too large for GitHub. Download them from [katagotraining.org](https://katagotraining.org/networks/):

```bash
cd app/src/main/assets/models/

# Human (10-block) - ~12MB
curl -L -o 10b.bin.gz "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b10c128-s1141046784-d204142634.bin.gz"

# Superhuman (18-block) - ~93MB
curl -L -o 18b.bin.gz "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz"

# Godlike (28-block) - ~259MB
curl -L -o 28b.bin.gz "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b28c512nbt-s12283775232-d5679728027.bin.gz"
```

### 2. Build the APK

Ensure you have Android SDK installed, then:

```bash
./gradlew assembleDebug
```

### 3. Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Credits & Acknowledgments

### KataGo Engine
- **KataGo** by [lightvector (David J. Wu)](https://github.com/lightvector/KataGo)
- One of the strongest open-source Go/Baduk engines
- Licensed under MIT License
- GitHub: https://github.com/lightvector/KataGo

### Neural Network Models
All neural networks are from the [KataGo Training](https://katagotraining.org/) distributed training project:

- **Human (10-block)**: `kata1-b10c128-s1141046784-d204142634`
- **Superhuman (18-block)**: `kata1-b18c384nbt-s9996604416-d4316597426`
- **Godlike (28-block)**: `kata1-b28c512nbt-s12283775232-d5679728027`

Networks are licensed under [CC0 (Public Domain)](https://creativecommons.org/publicdomain/zero/1.0/) for older g170 networks, and the [KataGo Network License](https://katagotraining.org/network_license/) for newer kata1 networks.

### Resources
- KataGo Releases: https://github.com/lightvector/KataGo/releases
- KataGo Training Networks: https://katagotraining.org/networks/
- KataGo Documentation: https://github.com/lightvector/KataGo/blob/master/README.md

## License

This app is provided for personal and educational use.
