#!/usr/bin/env bash
# 挂载点体检：校验 anchors.json 的完整性与版本元数据，并在条件允许时用 DexSQL
# 对目标 APK 逐项核对方法/字段锚点与类锚点。
#
# 用法:
#   bash tools/check-mounts.sh [--apk <path>] [--anchors <path>] [--dexsql <path>]
#
#   --apk      目标 APK；缺省时依次探测 $TIEBA_APK、dist/*.apk、仓库根 *.apk、
#              ~/Downloads/*.apk，取包名为 com.baidu.tieba 的第一个
#   --anchors  anchors.json 路径（默认 app/src/main/resources/anchors.json）
#   --dexsql   DexSQL 可执行文件（默认沿用 gen_anchors.py 的解析顺序）
#
# 退出码: 0 通过（含降级模式）；非 0 表示锚点表不完整、APK 不匹配或锚点未命中。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

APK_ARG="${TIEBA_APK:-}"
ANCHORS_ARG="$REPO_ROOT/app/src/main/resources/anchors.json"
DEXSQL_ARG="${DEXSQL:-}"

usage() {
    sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
    printf 'check-mounts: error: %s\n' "$*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            [ $# -ge 2 ] || die "--apk 缺少参数"
            APK_ARG="$2"
            shift 2
            ;;
        --anchors)
            [ $# -ge 2 ] || die "--anchors 缺少参数"
            ANCHORS_ARG="$2"
            shift 2
            ;;
        --dexsql)
            [ $# -ge 2 ] || die "--dexsql 缺少参数"
            DEXSQL_ARG="$2"
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) die "未知参数：$1（见 --help）" ;;
    esac
done

# Git Bash 下 /tmp、/e/... 这类 MSYS 路径 Python 无法直接识别，先转成 Windows 路径。
to_native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w -- "$1" 2>/dev/null || printf '%s' "$1"
    else
        printf '%s' "$1"
    fi
}
if [ -n "$APK_ARG" ]; then APK_ARG="$(to_native_path "$APK_ARG")"; fi
if [ -n "$ANCHORS_ARG" ]; then ANCHORS_ARG="$(to_native_path "$ANCHORS_ARG")"; fi
if [ -n "$DEXSQL_ARG" ]; then DEXSQL_ARG="$(to_native_path "$DEXSQL_ARG")"; fi

PYTHON="${PYTHON:-python}"
command -v "$PYTHON" >/dev/null 2>&1 \
    || die "未找到 python；安装 Python 3 或设置 PYTHON 环境变量"

cd "$REPO_ROOT"

status=0
CHECK_MOUNTS_REPO_ROOT="$REPO_ROOT" \
CHECK_MOUNTS_APK="$APK_ARG" \
CHECK_MOUNTS_ANCHORS="$ANCHORS_ARG" \
CHECK_MOUNTS_DEXSQL="$DEXSQL_ARG" \
"$PYTHON" -I - <<'PY' || status=$?
import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(os.environ["CHECK_MOUNTS_REPO_ROOT"])
TOOLS_DIR = REPO_ROOT / "tools"
sys.path.insert(0, str(TOOLS_DIR))

import gen_anchors as ga

SCHEMA_VERSION = 1
TARGET_PACKAGE = "com.baidu.tieba"

errors = []
warnings = []


def out(message=""):
    print(message)


def fail(message):
    errors.append(message)
    print("FAIL: %s" % message)


def warn(message):
    warnings.append(message)
    print("WARN: %s" % message)


def load_json_no_duplicates(path):
    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("重复键：%s" % key)
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=no_duplicates)


# ------------------------------------------------------------ 锚点表完整性
anchors_path = Path(os.environ["CHECK_MOUNTS_ANCHORS"]).expanduser()
if not anchors_path.is_absolute():
    anchors_path = (REPO_ROOT / anchors_path).resolve()
out("==> anchors: %s" % anchors_path)
if not anchors_path.is_file():
    out("FAIL: anchors.json 不存在：%s" % anchors_path)
    raise SystemExit(1)

try:
    payload = load_json_no_duplicates(anchors_path)
except (OSError, ValueError, json.JSONDecodeError) as exc:
    out("FAIL: 无法解析 anchors.json：%s" % exc)
    raise SystemExit(1)

if not isinstance(payload, dict):
    fail("anchors.json 顶层不是对象")
    payload = {}

app_version = payload.get("appVersion")
version_code = payload.get("versionCode")
schema_version = payload.get("schemaVersion")
generated_by = payload.get("generatedBy")
anchors = payload.get("anchors")
classes = payload.get("classes")

if isinstance(schema_version, bool) or schema_version != SCHEMA_VERSION:
    fail("schemaVersion 缺失或不是 %d：%r" % (SCHEMA_VERSION, schema_version))
if not isinstance(app_version, str) or not app_version.strip():
    fail("appVersion 缺失或不是非空字符串")
if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
    fail("versionCode 缺失或不是正整数")
if generated_by != "tools/gen_anchors.py":
    fail("generatedBy 不是 tools/gen_anchors.py：%r" % generated_by)
if not isinstance(anchors, dict):
    fail("anchors 缺失或不是对象")
    anchors = {}
if not isinstance(classes, dict):
    fail("classes 缺失或不是对象")
    classes = {}

overlap = sorted(set(anchors) & set(classes))
if overlap:
    fail("anchors 与 classes 存在同名 key：%s" % ", ".join(overlap))

for key, entry in sorted(anchors.items()):
    if not isinstance(key, str) or not key:
        fail("锚点 key 非法：%r" % (key,))
        continue
    if not isinstance(entry, dict):
        fail("锚点 %s 不是对象" % key)
        continue
    for field in ("c", "m", "s", "feature", "note"):
        value = entry.get(field)
        if not isinstance(value, str) or not value:
            fail("锚点 %s 缺少必填字段 %s" % (key, field))
    if not isinstance(entry.get("critical"), bool):
        fail("锚点 %s 的 critical 不是布尔值" % key)
    feature = entry.get("feature")
    if isinstance(feature, str) and feature \
            and not feature.startswith(("AD-", "UI-", "PV-", "EN-", "EX-")):
        fail("锚点 %s 的 feature 不是统一功能编号：%r" % (key, feature))

for key, cls in sorted(classes.items()):
    if not isinstance(key, str) or not key:
        fail("类锚点 key 非法：%r" % (key,))
    if not isinstance(cls, str) or not cls:
        fail("类锚点 %s 不是非空字符串" % key)

# 版本元数据与 README 首屏目标版本保持一致。
try:
    readme_text = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
except OSError:
    readme_text = ""
match = re.search(
    r"目标版本：百度贴吧 \*\*([0-9][0-9.]*)\*\*（versionCode ([0-9]+)）",
    readme_text,
)
if match:
    readme_version, readme_code = match.group(1), int(match.group(2))
    if app_version != readme_version or version_code != readme_code:
        fail(
            "anchors.json 版本元数据 %s/%s 与 README 目标版本 %s/%s 不一致"
            % (app_version, version_code, readme_version, readme_code)
        )
else:
    warn("未能从 README.md 解析目标版本，跳过版本元数据对照")

out("==> 锚点表：%d anchors + %d classes（schema v%d，appVersion %s / versionCode %s）"
    % (len(anchors), len(classes), schema_version, app_version, version_code))
if not errors:
    out("==> 锚点表完整性 OK（schema、必填字段、feature/critical、版本元数据）")

# ------------------------------------------------------------ APK / DexSQL
apk_arg = os.environ.get("CHECK_MOUNTS_APK", "").strip()
dexsql_arg = os.environ.get("CHECK_MOUNTS_DEXSQL", "").strip()

try:
    aapt = ga.resolve_aapt(None)
except SystemExit:
    aapt = None
try:
    apkanalyzer = ga.resolve_apkanalyzer(None)
except SystemExit:
    apkanalyzer = None


def read_package(apk):
    if aapt:
        output = ga._run([str(aapt), "dump", "badging", str(apk)])
        if output:
            found = re.search(r"package: name='([^']+)'", output)
            if found:
                return found.group(1)
    if apkanalyzer:
        return ga._apkanalyzer_value(apkanalyzer, "application-id", apk)
    return None


def apk_candidates():
    seen = set()
    candidates = []
    if apk_arg:
        candidates.append(Path(apk_arg).expanduser())
    for path in sorted((REPO_ROOT / "dist").glob("*.apk")):
        candidates.append(path)
    for path in sorted(REPO_ROOT.glob("*.apk")):
        candidates.append(path)
    for path in sorted((Path.home() / "Downloads").glob("*.apk")):
        candidates.append(path)
    unique = []
    for path in candidates:
        key = str(path)
        if key not in seen:
            seen.add(key)
            unique.append(path)
    return unique


apk = None
if apk_arg:
    apk = Path(apk_arg).expanduser()
    if not apk.is_file():
        fail("APK 不存在：%s" % apk)
        apk = None
else:
    for candidate in apk_candidates():
        if not candidate.is_file():
            continue
        if read_package(candidate) == TARGET_PACKAGE:
            apk = candidate
            break

apk_package = None
apk_version = None
apk_version_code = None
if apk is not None:
    apk_package = read_package(apk)
    apk_version, apk_version_code = ga.read_apk_version(apk, aapt, apkanalyzer)
    out("==> APK: %s" % apk)
    if apk_package is None:
        warn("无法读取 APK 包名（aapt2/apkanalyzer 不可用）")
    elif apk_package != TARGET_PACKAGE:
        fail("APK 包名 %s，预期 %s" % (apk_package, TARGET_PACKAGE))
    else:
        out("==> APK 版本：%s（versionCode %s）" % (apk_version or "未知", apk_version_code or "未知"))
        if apk_version and (apk_version != app_version or str(apk_version_code) != str(version_code)):
            warn(
                "APK 版本与 anchors.json 元数据不一致（%s/%s vs %s/%s）；"
                "适配新宿主时属预期，全量模式仍逐项校验"
                % (apk_version, apk_version_code, app_version, version_code)
            )
else:
    out("==> APK：未指定且未自动探测到 %s 安装包" % TARGET_PACKAGE)

dexsql = None
dexsql_reason = ""
try:
    dexsql = ga.resolve_dexsql(dexsql_arg or None)
except SystemExit as exc:
    dexsql_reason = str(exc).strip()

if dexsql is None or apk is None:
    reasons = []
    if dexsql is None:
        reasons.append(dexsql_reason or "dexsql 不可用")
    if apk is None:
        reasons.append("未找到目标 APK")
    out("==> 降级模式：%s；只校验锚点表完整性，未对 APK 做逐项校验" % "；".join(reasons))
    if errors:
        out("==> 结果：FAIL（%d 项）" % len(errors))
        raise SystemExit(1)
    out("==> 结果：锚点表 OK（降级模式）；anchors %d，classes %d"
        % (len(anchors), len(classes)))
    raise SystemExit(0)

out("==> dexsql: %s" % dexsql)


def query_count(sql):
    output = ga.dexsql(dexsql, apk, sql)
    rows = ga.table_rows(output)
    if not rows:
        return 0
    try:
        return int(rows[-1][0])
    except (ValueError, IndexError):
        return 0


method_ok = method_sig = method_gone = 0
field_ok = field_sig = field_gone = 0
class_ok = class_gone = 0

for key, entry in sorted(anchors.items()):
    is_method = entry["s"].startswith("(")
    table = "methods" if is_method else "fields"
    if is_method:
        descriptor = "L%s;->%s%s" % (
            entry["c"].replace(".", "/"), entry["m"], entry["s"],
        )
    else:
        descriptor = "L%s;->%s:%s" % (
            entry["c"].replace(".", "/"), entry["m"], entry["s"],
        )
    try:
        sql = "SELECT count(*) AS c FROM %s WHERE descriptor='%s'" % (table, descriptor)
        if is_method:
            # 只统计已定义方法；methods 表里 method_kind 为空的是仅被引用的方法。
            sql += " AND method_kind IS NOT NULL"
        count = query_count(sql)
    except RuntimeError as exc:
        fail("dexsql 查询%s %s 失败：%s"
             % ("方法" if is_method else "字段", key, exc))
        continue
    if count > 0:
        if is_method:
            method_ok += 1
        else:
            field_ok += 1
        continue
    try:
        class_count = query_count(
            "SELECT count(*) AS c FROM types WHERE java_name='%s'" % entry["c"]
        )
    except RuntimeError as exc:
        fail("dexsql 查询类 %s 失败：%s" % (entry["c"], exc))
        continue
    if class_count > 0:
        if is_method:
            method_sig += 1
        else:
            field_sig += 1
        print("SIG %s: %s" % (key, descriptor))
    else:
        if is_method:
            method_gone += 1
        else:
            field_gone += 1
        print("GONE %s: %s" % (key, descriptor))

for key, cls in sorted(classes.items()):
    try:
        count = query_count(
            "SELECT count(*) AS c FROM types WHERE java_name='%s'" % cls
        )
    except RuntimeError as exc:
        fail("dexsql 查询类 %s 失败：%s" % (key, exc))
        continue
    if count > 0:
        class_ok += 1
    else:
        class_gone += 1
        print("GONE class %s: %s" % (key, cls))

if method_sig or method_gone:
    fail("方法锚点未全命中：SIG %d / GONE %d" % (method_sig, method_gone))
if field_sig or field_gone:
    fail("字段锚点未全命中：SIG %d / GONE %d" % (field_sig, field_gone))
if class_gone:
    fail("类锚点未全命中：GONE %d" % class_gone)

out("==> 结果：methods OK %d/%d（SIG %d / GONE %d）；"
    "fields OK %d/%d（SIG %d / GONE %d）；classes OK %d/%d（GONE %d）"
    % (method_ok, method_ok + method_sig + method_gone, method_sig, method_gone,
       field_ok, field_ok + field_sig + field_gone, field_sig, field_gone,
       class_ok, class_ok + class_gone, class_gone))
if errors:
    out("==> 结果：FAIL（%d 项）" % len(errors))
    raise SystemExit(1)
out("==> check-mounts OK")
raise SystemExit(0)
PY
exit "$status"
