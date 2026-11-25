# source 这个文件后再跑 gradle / adb。不要直接执行。
# macOS 仍写入作者本机的工具链路径，和原先三行赋值相同。
# 其它系统不覆盖已经设好的 JAVA_HOME、ANDROID_HOME 和 PATH；缺了就失败。
# 这里不下载工具链，也不读取签名材料。
# UNITEDU_TOOLCHAIN_OS 仅用于测试指定系统名，平时不要设置。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "请 source scripts/env.sh，不要直接执行。" >&2
  exit 2
fi

unitedu_toolchain_os() {
  if [[ -n "${UNITEDU_TOOLCHAIN_OS:-}" ]]; then
    printf '%s' "${UNITEDU_TOOLCHAIN_OS}"
    return 0
  fi
  uname -s
}

unitedu_apply_toolchain_env() {
  local os="$1"
  if [[ "$os" == "Darwin" ]]; then
    export JAVA_HOME="$HOME/Library/Java/jdk-17/Contents/Home"
    export ANDROID_HOME="$HOME/Library/Android/sdk"
    export PATH="$HOME/Library/Gradle/gradle-8.14.5/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
    return 0
  fi
  if [[ -z "${JAVA_HOME:-}" || ! -d "${JAVA_HOME}" ]]; then
    echo "UnitedU: 非 macOS 工具链必须已经设好 JAVA_HOME（JDK 17 目录）。scripts/env.sh 不会填写或覆盖它。" >&2
    return 1
  fi
  if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME}" ]]; then
    echo "UnitedU: 非 macOS 工具链必须已经设好 ANDROID_HOME（含 platforms/android-35 与 build-tools/35.0.0）。scripts/env.sh 不会填写或覆盖它。" >&2
    return 1
  fi
  if ! command -v gradle >/dev/null 2>&1 || [[ ! -x "$(command -v gradle)" ]]; then
    echo "UnitedU: 非 macOS 工具链必须 PATH 上已有可执行的 gradle（8.14.5）。scripts/env.sh 不会下载 Gradle，也不会改 PATH。" >&2
    return 1
  fi
  return 0
}

_unitedu_os="$(unitedu_toolchain_os)"
case "${_unitedu_os}" in
  ""|*[!A-Za-z0-9_.-]*)
    unset _unitedu_os
    echo "UnitedU: 无法识别的系统名。" >&2
    return 1
    ;;
esac
if ! unitedu_apply_toolchain_env "${_unitedu_os}"; then
  unset _unitedu_os
  return 1
fi
unset _unitedu_os
