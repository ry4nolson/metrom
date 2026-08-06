# Metrom iOS

Native SwiftUI app consuming the KMP `MetromShared` framework.

## Prerequisites

- Full Xcode (not only Command Line Tools). If `xcode-select` points at CLT:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

- JDK 17+ for the shared framework Gradle build.

## Build shared framework (simulator)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 19 2>/dev/null || /usr/libexec/java_home)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Output: `shared/build/bin/iosSimulatorArm64/debugFramework/MetromShared.framework`

## Generate / open Xcode project

```bash
ruby scripts/generate-ios-xcodeproj.rb
open iosApp/Metrom.xcodeproj
```

Or build from the CLI:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd iosApp
xcodebuild -project Metrom.xcodeproj -scheme Metrom -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.6' \
  -derivedDataPath "$PWD/build/DerivedData" \
  CODE_SIGN_IDENTITY="-" build

xcrun simctl install booted \
  "$PWD/build/DerivedData/Build/Products/Debug-iphonesimulator/Metrom.app"
xcrun simctl launch booted com.metrom.app
```

Tone samples must land at `Metrom.app/tones/...` (not under a top-level `Resources/` folder — that breaks simulator install).

## Feature parity (v1)

BPM / meter / subdivisions / swing / accents / group tempo, synth + chug/kick,
ONE/OTHERS pitch, tap tempo, haptics, count-in, mute bars, tempo trainer, songs,
background audio + Now Playing, mic listen (pick from candidates; disabled while playing).
