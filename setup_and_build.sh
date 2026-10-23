#!/usr/bin/env bash
set -e

echo "=== HMVideo Android Toolchain & Build Automation ==="

CACHE_DIR="$(pwd)/.build_cache"
mkdir -p "$CACHE_DIR" /opt/android-sdk /opt/android-libs

# 1. Ensure JDK 17 and zip are installed
if ! command -v javac &> /dev/null; then
  echo "[Setup] Installing OpenJDK 17..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends \
    -o Dpkg::Options::="--force-confdef" \
    -o Dpkg::Options::="--force-confold" \
    openjdk-17-jdk-headless zip
fi

# 2. Android Build-Tools 34
if [ ! -f /opt/android-sdk/build-tools/34.0.0/aapt2 ]; then
  if [ ! -f "$CACHE_DIR/build-tools.zip" ]; then
    echo "[Setup] Downloading Android Build-Tools 34..."
    curl -sL -o "$CACHE_DIR/build-tools.zip" "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
  fi
  echo "[Setup] Extracting Build-Tools 34..."
  mkdir -p /tmp/bt
  unzip -q -o "$CACHE_DIR/build-tools.zip" -d /tmp/bt
  mkdir -p /opt/android-sdk/build-tools/34.0.0
  cp -r /tmp/bt/*/* /opt/android-sdk/build-tools/34.0.0/
  chmod +x /opt/android-sdk/build-tools/34.0.0/*
  rm -rf /tmp/bt
fi

# 3. Android Platform 34 (android.jar)
if [ ! -f /opt/android-sdk/platforms/android-34/android.jar ]; then
  if [ ! -f "$CACHE_DIR/platform-34.zip" ]; then
    echo "[Setup] Downloading Android Platform 34..."
    curl -sL -o "$CACHE_DIR/platform-34.zip" "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
  fi
  echo "[Setup] Extracting Platform 34..."
  mkdir -p /tmp/plat
  unzip -q -o "$CACHE_DIR/platform-34.zip" -d /tmp/plat
  mkdir -p /opt/android-sdk/platforms/android-34
  cp -r /tmp/plat/*/* /opt/android-sdk/platforms/android-34/
  rm -rf /tmp/plat
fi

# 4. Kotlin Compiler 1.9.23
if [ ! -f /opt/kotlinc/bin/kotlinc ]; then
  if [ ! -f "$CACHE_DIR/kotlinc.zip" ]; then
    echo "[Setup] Downloading Kotlin Compiler 1.9.23..."
    curl -sL -o "$CACHE_DIR/kotlinc.zip" "https://github.com/JetBrains/kotlin/releases/download/v1.9.23/kotlin-compiler-1.9.23.zip"
  fi
  echo "[Setup] Extracting Kotlinc..."
  unzip -q -o "$CACHE_DIR/kotlinc.zip" -d /opt/
  chmod +x /opt/kotlinc/bin/*
fi

# 5. Maven Dependencies
echo "[Setup] Ensuring Android libraries..."
for jar in "okhttp-4.12.0.jar:https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" \
           "okio-jvm-3.6.0.jar:https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" \
           "jsoup-1.17.2.jar:https://repo1.maven.org/maven2/org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar" \
           "gson-2.10.1.jar:https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"; do
  file="${jar%%:*}"
  url="${jar#*:}"
  if [ ! -f "$CACHE_DIR/$file" ]; then
    curl -sL -o "$CACHE_DIR/$file" "$url"
  fi
  cp "$CACHE_DIR/$file" "/opt/android-libs/$file"
done

# 6. Keystore
if [ ! -f /opt/debug.keystore ]; then
  echo "[Setup] Generating debug.keystore..."
  keytool -genkeypair -v -keystore /opt/debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

echo "=== ENVIRONMENT READY! NOW COMPILING APK ==="
bash build_apk.sh
