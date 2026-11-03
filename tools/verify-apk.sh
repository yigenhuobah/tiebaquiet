#!/usr/bin/env bash
# 校验 release APK：Xposed 元数据、scope、入口、R8 keep、Manifest、版本号、签名证书。
#
# 用法：
#   bash tools/verify-apk.sh [APK路径] [--expected-version X] [--expected-code N] [--out-dir DIR]
#
# 默认 APK：app/build/outputs/apk/release/tiebaquiet-<versionName>.apk
# 不传 --expected-* 时以 app/build.gradle 的 versionName/versionCode 为准。
# 全部检查通过退出 0；任一失败退出 1；工具缺失时打印修复提示并退出 2。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle"
CONFIG_STORE="$ROOT_DIR/app/src/main/java/io/github/yigenhuobah/tiebaquiet/ConfigStore.java"

EXPECTED_CERT_SHA256="2453a611bb3eeff59489fd1d0578ca12be858f578ec86e0ec8a25176554fc071"
EXPECTED_SCOPE="com.baidu.tieba"
EXPECTED_ENTRY="io.github.yigenhuobah.tiebaquiet.TiebaQuietModule"
REQUIRED_CLASSES=(
    "io.github.yigenhuobah.tiebaquiet.TiebaQuietModule"
    "io.github.yigenhuobah.tiebaquiet.StatusProvider"
    "io.github.yigenhuobah.tiebaquiet.MaterialSwitchPreference"
)

APK_ARG=""
EXPECTED_VERSION=""
EXPECTED_CODE=""
OUT_DIR=""
FAILED=0
PYTHON_BIN="$(command -v python3 || command -v python || true)"

usage() {
    sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
    printf 'verify-apk: error: %s\n' "$*" >&2
    exit 2
}

ok() {
    printf '  [ok] %s\n' "$*"
}

bad() {
    printf '  [FAIL] %s\n' "$*" >&2
    FAILED=$((FAILED + 1))
}

while [ $# -gt 0 ]; do
    case "$1" in
        --expected-version)
            [ $# -ge 2 ] || die "--expected-version 需要参数"
            EXPECTED_VERSION="$2"
            shift 2
            ;;
        --expected-code)
            [ $# -ge 2 ] || die "--expected-code 需要参数"
            EXPECTED_CODE="$2"
            shift 2
            ;;
        --out-dir)
            [ $# -ge 2 ] || die "--out-dir 需要参数"
            OUT_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            die "未知参数：$1（见 --help）"
            ;;
        *)
            [ -z "$APK_ARG" ] || die "只能指定一个 APK 路径"
            APK_ARG="$1"
            shift
            ;;
    esac
done

# ---------- 读取 app/build.gradle 版本号 ----------
[ -f "$GRADLE_FILE" ] || die "找不到 $GRADLE_FILE"
BUILD_VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$GRADLE_FILE" | head -1)"
# versionName 兼容单引号（versionName '0.3.3'）与双引号（versionName "0.3.3"）两种写法。
BUILD_VERSION_NAME="$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+['\"]([^'\"]+)['\"].*/\1/p" "$GRADLE_FILE" | head -1)"
[ -n "$BUILD_VERSION_CODE" ] || die "无法从 app/build.gradle 读取 versionCode"
[ -n "$BUILD_VERSION_NAME" ] || die "无法从 app/build.gradle 读取 versionName"

EXPECTED_CODE="${EXPECTED_CODE:-$BUILD_VERSION_CODE}"
EXPECTED_VERSION="${EXPECTED_VERSION:-$BUILD_VERSION_NAME}"

if [ -z "$APK_ARG" ]; then
    APK_ARG="$ROOT_DIR/app/build/outputs/apk/release/tiebaquiet-${BUILD_VERSION_NAME}.apk"
fi
[ -f "$APK_ARG" ] || die "APK 不存在：$APK_ARG（先运行 ./gradlew :app:assembleRelease）"
APK="$(cd "$(dirname "$APK_ARG")" && pwd)/$(basename "$APK_ARG")"
APK_WIN="$(cygpath -w "$APK" 2>/dev/null || printf '%s' "$APK")"

# ---------- 发现 Android SDK 工具 ----------
# local.properties 的 sdk.dir 是 java properties 转义形式，这里宽松地还原成 POSIX 路径。
sdk_dir_from_local_properties() {
    [ -f "$ROOT_DIR/local.properties" ] || return 1
    local line value
    line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$ROOT_DIR/local.properties" | head -1 || true)"
    [ -n "$line" ] || return 1
    value="${line#*=}"
    printf '%s' "$value" | tr '\\' '/' 2>/dev/null \
        | sed -e 's|/:|:|g' -e 's|//*|/|g'
}

discover_sdk_root() {
    local candidate posix
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                     "$(sdk_dir_from_local_properties || true)" \
                     "/d/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "/c/Android/Sdk"; do
        [ -n "$candidate" ] || continue
        posix="$(cygpath -u "$candidate" 2>/dev/null || printf '%s' "$candidate")"
        if [ -d "$posix/build-tools" ]; then
            printf '%s\n' "$posix"
            return 0
        fi
    done
    return 1
}

SDK_ROOT="$(discover_sdk_root || true)"
if [ -z "$SDK_ROOT" ]; then
    cat >&2 <<'EOF'
verify-apk: error: 找不到 Android SDK。
修复：
  1. 设置 ANDROID_HOME（或 ANDROID_SDK_ROOT）指向 SDK 根目录；
  2. 或在 local.properties 写入 sdk.dir=D:\\Android\\Sdk；
  3. 或安装 build-tools：sdkmanager "build-tools;35.0.0"。
EOF
    exit 2
fi

BT_DIR=""
if [ -d "$SDK_ROOT/build-tools" ]; then
    while IFS= read -r version; do
        if [ -d "$SDK_ROOT/build-tools/$version" ]; then
            BT_DIR="$SDK_ROOT/build-tools/$version"
            break
        fi
    done < <(ls -1 "$SDK_ROOT/build-tools" 2>/dev/null | sort -V -r)
fi
[ -n "$BT_DIR" ] || die "SDK 中没有 build-tools：$SDK_ROOT/build-tools"

find_tool() {
    local name
    for name in "$@"; do
        if [ -f "$BT_DIR/$name" ]; then
            printf '%s\n' "$BT_DIR/$name"
            return 0
        fi
    done
    return 1
}

AAPT2="$(find_tool aapt2.exe aapt2 || true)"
APKSIGNER="$(find_tool apksigner.bat apksigner || true)"
DEXDUMP="$(find_tool dexdump.exe dexdump || true)"
APKANALYZER=""
for candidate in \
    "$SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer.bat" \
    "$SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer" \
    "$SDK_ROOT/tools/bin/apkanalyzer.bat" \
    "$SDK_ROOT/tools/bin/apkanalyzer"; do
    if [ -f "$candidate" ]; then
        APKANALYZER="$candidate"
        break
    fi
done

[ -n "$AAPT2" ] || die "未找到 aapt2。修复：sdkmanager \"build-tools;35.0.0\"（当前查找目录 $BT_DIR）"
[ -n "$APKSIGNER" ] || die "未找到 apksigner。修复：sdkmanager \"build-tools;35.0.0\"（当前查找目录 $BT_DIR）"
if [ -z "$DEXDUMP" ] && [ -z "$APKANALYZER" ]; then
    die "未找到 dexdump/apkanalyzer。修复：sdkmanager \"build-tools;35.0.0\" 或 \"cmdline-tools;latest\""
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

printf 'verify-apk: %s\n' "$APK"
printf '  SDK build-tools: %s\n' "$BT_DIR"

# ---------- 解包 APK（只写临时目录） ----------
unzip -o -q "$APK" -d "$TMP_DIR" || die "解包 APK 失败：$APK"
XPOSED_DIR="$TMP_DIR/META-INF/xposed"

# 1. module.prop
if [ -f "$XPOSED_DIR/module.prop" ]; then
    prop_get() {
        grep -m1 "^$1=" "$XPOSED_DIR/module.prop" 2>/dev/null | cut -d= -f2- | tr -d '\r' || true
    }
    for pair in "minApiVersion=102" "targetApiVersion=102" "staticScope=true" \
                "exceptionMode=protective" "autoHotReload=false"; do
        key="${pair%%=*}"
        want="${pair#*=}"
        got="$(prop_get "$key")"
        if [ "$got" = "$want" ]; then
            ok "module.prop: $key=$got"
        else
            bad "module.prop: $key 期望 '$want'，实际 '${got:-<缺失>}'"
        fi
    done
else
    bad "APK 内缺少 META-INF/xposed/module.prop"
fi

# 2. scope.list
if [ -f "$XPOSED_DIR/scope.list" ]; then
    mapfile -t SCOPES < <(tr -d '\r' < "$XPOSED_DIR/scope.list" | sed '/^[[:space:]]*$/d')
    if [ "${#SCOPES[@]}" -eq 1 ] && [ "${SCOPES[0]}" = "$EXPECTED_SCOPE" ]; then
        ok "scope.list: 仅包含 $EXPECTED_SCOPE"
    else
        bad "scope.list 期望仅 '$EXPECTED_SCOPE'，实际：$(printf '%s ' "${SCOPES[@]:-<空>}")"
    fi
else
    bad "APK 内缺少 META-INF/xposed/scope.list"
fi

# 3. java_init.list
if [ -f "$XPOSED_DIR/java_init.list" ]; then
    mapfile -t INIT_ENTRIES < <(tr -d '\r' < "$XPOSED_DIR/java_init.list" | sed '/^[[:space:]]*$/d')
    if [ "${#INIT_ENTRIES[@]}" -eq 1 ] && [[ "${INIT_ENTRIES[0]}" == *"$EXPECTED_ENTRY"* ]]; then
        ok "java_init.list: 单入口 ${INIT_ENTRIES[0]}"
    else
        bad "java_init.list 期望单入口且包含 $EXPECTED_ENTRY，实际：$(printf '%s ' "${INIT_ENTRIES[@]:-<空>}")"
    fi
else
    bad "APK 内缺少 META-INF/xposed/java_init.list"
fi

# 4. R8 keep：从 dex 中提取类名
CLASSES_FILE="$TMP_DIR/classes.txt"
: > "$CLASSES_FILE"
if [ -n "$DEXDUMP" ]; then
    shopt -s nullglob
    DEX_FILES=("$TMP_DIR"/classes*.dex)
    shopt -u nullglob
    [ "${#DEX_FILES[@]}" -gt 0 ] || die "APK 内没有 classes*.dex"
    for dex_file in "${DEX_FILES[@]}"; do
        "$DEXDUMP" -l plain "$dex_file" 2>/dev/null \
            | sed -n "s/.*Class descriptor  : 'L\(.*\);'.*/\1/p" \
            | tr '/' '.' >> "$CLASSES_FILE"
    done
else
    "$APKANALYZER" dex packages --defined-only "$APK_WIN" 2>/dev/null \
        | awk '$1 == "C" { print $NF }' >> "$CLASSES_FILE"
fi
[ -s "$CLASSES_FILE" ] || die "未能从 APK 提取 dex 类列表"

for cls in "${REQUIRED_CLASSES[@]}"; do
    if grep -qxF "$cls" "$CLASSES_FILE"; then
        ok "dex keep: $cls"
    else
        bad "dex 中缺少（可能被 R8 改名/移除）：$cls"
    fi
done

# 5. Manifest：debuggable=false / allowBackup=false
MANIFEST_TREE="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK_WIN" 2>/dev/null || true)"
if [ -z "$MANIFEST_TREE" ]; then
    bad "无法读取 APK 的 AndroidManifest.xml"
else
    if printf '%s\n' "$MANIFEST_TREE" | grep -qE ':debuggable\(0x[0-9a-fA-F]+\)='; then
        if printf '%s\n' "$MANIFEST_TREE" | grep -qE ':debuggable\(0x[0-9a-fA-F]+\)=(\(type 0x12\))?(true|0x1)\b'; then
            bad "Manifest debuggable=true"
        else
            ok "Manifest debuggable=false"
        fi
    else
        ok "Manifest 未声明 debuggable（等价 false）"
    fi
    if printf '%s\n' "$MANIFEST_TREE" | grep -qE ':allowBackup\(0x[0-9a-fA-F]+\)=(\(type 0x12\))?(false|0x0)\b'; then
        ok "Manifest allowBackup=false"
    else
        bad "Manifest allowBackup 不是 false"
    fi
fi

# 6. aapt2 badging 版本号
BADGING="$("$AAPT2" dump badging "$APK_WIN" 2>/dev/null | head -1 || true)"
ACTUAL_CODE="$(printf '%s' "$BADGING" | sed -nE "s/.*versionCode='([^']*)'.*/\1/p")"
ACTUAL_VERSION="$(printf '%s' "$BADGING" | sed -nE "s/.*versionName='([^']*)'.*/\1/p")"
if [ "$ACTUAL_CODE" = "$EXPECTED_CODE" ]; then
    ok "versionCode=$ACTUAL_CODE"
else
    bad "versionCode 期望 $EXPECTED_CODE，实际 '${ACTUAL_CODE:-<未知>}'"
fi
if [ "$ACTUAL_VERSION" = "$EXPECTED_VERSION" ]; then
    ok "versionName=$ACTUAL_VERSION"
else
    bad "versionName 期望 $EXPECTED_VERSION，实际 '${ACTUAL_VERSION:-<未知>}'"
fi

# 6b. APK 内 anchors.json 必须存在且版本与本次发布一致。
# 改了目标版本却忘记重新生成锚点表时，运行时会全部 fail-open；这里在发版前硬失败。
if [ ! -f "$TMP_DIR/anchors.json" ]; then
    bad "APK 内缺少 anchors.json（锚点表未打包，运行时会全部 fail-open）"
elif [ -z "$PYTHON_BIN" ]; then
    bad "未找到 python3/python，无法校验 anchors.json 版本"
else
    ANCHORS_VERSION="$("$PYTHON_BIN" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("appVersion",""))' "$TMP_DIR/anchors.json" 2>/dev/null || true)"
    ANCHORS_CODE="$("$PYTHON_BIN" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("versionCode",""))' "$TMP_DIR/anchors.json" 2>/dev/null || true)"
    TARGET_VERSION="$(sed -nE 's/.*TARGET_VERSION[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$CONFIG_STORE" | head -1)"
    if [ -z "$TARGET_VERSION" ]; then
        bad "无法从 ConfigStore.java 读取 TARGET_VERSION"
    elif [ "$ANCHORS_VERSION" = "$TARGET_VERSION" ]; then
        ok "anchors.json appVersion=$ANCHORS_VERSION (target)"
    else
        bad "anchors.json appVersion 期望目标应用版本 $TARGET_VERSION，实际 '${ANCHORS_VERSION:-<未找到>}'"
    fi
    ok "anchors.json versionCode=${ANCHORS_CODE:-<未提供>}"
fi

# 7. 签名证书 SHA-256
SIGNER_OUTPUT="$("$APKSIGNER" verify --print-certs "$APK_WIN" 2>&1 || true)"
CERT_SHA="$(printf '%s\n' "$SIGNER_OUTPUT" \
    | grep -m1 'certificate SHA-256 digest' \
    | sed -E 's/.*digest:[[:space:]]*//' | tr 'A-F' 'a-f' || true)"
if [ "$CERT_SHA" = "$EXPECTED_CERT_SHA256" ]; then
    ok "签名证书 SHA-256=$CERT_SHA"
else
    bad "签名证书 SHA-256 期望 $EXPECTED_CERT_SHA256，实际 '${CERT_SHA:-<未找到>}'"
    printf '%s\n' "$SIGNER_OUTPUT" | sed 's/^/       /' >&2
fi

# 8. APK SHA-256
if command -v sha256sum >/dev/null 2>&1; then
    APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
else
    APK_SHA="$(certutil -hashfile "$APK_WIN" SHA256 2>/dev/null | sed -n '2p' | tr -d ' \r' | tr 'A-F' 'a-f')"
fi
[ -n "$APK_SHA" ] || die "无法计算 APK SHA-256"
ok "APK SHA-256=$APK_SHA"

if [ -n "$OUT_DIR" ]; then
    mkdir -p "$OUT_DIR"
    printf '%s  %s\n' "$APK_SHA" "$(basename "$APK")" > "$OUT_DIR/SHA256SUMS"
    ok "SHA256SUMS 已写入 $OUT_DIR/SHA256SUMS"
fi

if [ "$FAILED" -ne 0 ]; then
    printf 'verify-apk: FAIL（%d 项）\n' "$FAILED" >&2
    exit 1
fi
printf 'verify-apk: PASS\n'
