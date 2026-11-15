#!/usr/bin/env bash
# Idempotent environment setup for A55 WiFi Radar (OpenJDK 17 + Android SDK 35 + Dual-CA Truststore)

export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="/home/antigravity/.android-sdk"
export ANDROID_SDK_ROOT="/home/antigravity/.android-sdk"
export TMPDIR="/home/antigravity/.tmp"

mkdir -p "${TMPDIR}"

_TRUSTSTORE_OPTS="-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit"
case "${JAVA_TOOL_OPTIONS:-}" in
  *"javax.net.ssl.trustStore="*) ;;
  "") export JAVA_TOOL_OPTIONS="${_TRUSTSTORE_OPTS}" ;;
  *)  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} ${_TRUSTSTORE_OPTS}" ;;
esac
unset _TRUSTSTORE_OPTS

for _dir in \
  "${ANDROID_HOME}/cmdline-tools/latest/bin" \
  "${ANDROID_HOME}/platform-tools" \
  "${ANDROID_HOME}/build-tools/35.0.1" \
  "${JAVA_HOME}/bin"
do
  case ":${PATH}:" in
    *":${_dir}:"*) ;;
    *) export PATH="${_dir}:${PATH}" ;;
  esac
done
unset _dir
