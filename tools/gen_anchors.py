#!/usr/bin/env python3
"""
生成 anchors.json：用 DexSQL 校验每个 hook 锚点在目标 APK 中存在，只输出命中项。

用法:
    python tools/gen_anchors.py --apk /path/to/tieba.apk \
        --out app/src/main/resources/anchors.json

    # 找不到 dexsql 时显式指定
    python tools/gen_anchors.py --apk ... --out ... --dexsql /path/to/dexsql

dexsql 解析顺序: --dexsql > DEXSQL 环境变量 > PATH > 仓库 tools/dexsql/ > 常见安装路径。
appVersion/versionCode 默认从 --apk 读取（aapt2/aapt/apkanalyzer）。工具不可用或读取
失败时，必须显式传 --app-version / --version-code，否则报错退出。

所有锚点都命中后才写 --out；缺失时只打印 MISSING 并以非 0 退出，原文件保持不变。
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

# key: (class java name, method name, JVM descriptor of parameters+return)
ANCHORS = {
    # ---------- phase 1 ----------
    "splash_forbid_b": ("com.baidu.tieba.ad.under.utils.SplashForbidAdHelperKt", "b", "()Z"),
    "yun_dialog_can_show": ("com.baidu.tbadk.core.dialog.yun.YunDialogManager", "canShowDialog", "(Lcom/baidu/tieba/od6;)Z"),
    "home_ad_float": ("com.baidu.tbadk.TbSingleton", "getHomeAdFloatViewItemData", "()Lcom/baidu/tieba/fo6;"),
    "pb_ad_float": ("com.baidu.tbadk.TbSingleton", "getPbAdFloatViewItemData", "()Lcom/baidu/tieba/fo6;"),
    "template_set_list": ("com.baidu.tieba.feed.list.TemplateAdapter", "setList", "(Ljava/util/List;)V"),
    "tab_add_tip": ("com.baidu.tbadk.mainTab.TbFragmentTabIndicator", "addTip", "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V"),
    "tab_add_tip_2": ("com.baidu.tbadk.mainTab.FragmentTabIndicator", "addTip", "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V"),
    "tab_add_tip_3": ("com.baidu.tbadk.mainTab.MainTabBottomOptIndicator", "addTip", "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V"),
    "tab_add_tip_4": ("com.baidu.tbadk.mainTab.MaintabBottomIndicator", "addTip", "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V"),
    "tab_add_tip_wrap": ("com.baidu.tbadk.mainTab.TbFragmentTabIndicator", "addTipWrapContent", "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V"),
    "msg_red_dot_refresh": ("com.baidu.tbadk.core.view.MessageRedDotView", "refresh", "(I)V"),
    "msg_red_dot_refresh_opt": ("com.baidu.tbadk.core.view.MessageRedDotView", "refreshFeedUIOpt", "(I)V"),
    "msg_red_dot_refresh_str": ("com.baidu.tbadk.core.view.MessageRedDotView", "refresh", "(Ljava/lang/String;Z)V"),
    "rec_forum_strategy_h": ("com.baidu.tieba.forum.strategy.RecommendForumShowStrategy", "h", "(Lcom/baidu/tieba/y7a;)Z"),
    "upgrade_pop": ("com.baidu.tbadk.core.util.UpgradePopWindowHelper", "showPopWindow", "(Lcom/baidu/tbadk/TbPageContext;Lcom/baidu/tieba/op6;)V"),
    "easter_egg_o": ("com.baidu.tieba.easteregg.EasterEggAdController", "o", "(Lcom/baidu/tieba/easteregg/data/EasterEggAdData;)V"),
    "new_scroll_r": ("com.baidu.tieba.homepage.framework.indicator.NewScrollFragmentAdapter", "r", "(Landroid/content/Context;Ljava/util/List;)V"),
    "skin_type": ("com.baidu.tbadk.core.TbadkCoreApplication", "getSkinType", "()I"),
    "forum_res_parser_a": ("com.baidu.tieba.forum.data.protobuf.BaseForumResParser", "a", "(Ljava/lang/Object;Lcom/baidu/tieba/ej9;Ljava/util/Map;ZLjava/util/Map;)Ljava/util/List;"),

    # ---------- reflection helpers (filtering) ----------
    "nt9_d": ("com.baidu.tieba.nt9", "d", "()Ljava/lang/Object;"),
    "nt9_b": ("com.baidu.tieba.nt9", "b", "()Ljava/lang/String;"),
    "thread_get_is_live": ("com.baidu.tbadk.core.data.ThreadData", "getIsLive", "()I"),
    "thread_is_interview_live": ("com.baidu.tbadk.core.data.ThreadData", "isInterviewLive", "()Z"),
    "thread_is_live_thread": ("com.baidu.tbadk.core.data.ThreadData", "isLiveThread", "()Z"),
    "thread_is_live_interview_type": ("com.baidu.tbadk.core.data.ThreadData", "isLiveInterviewLiveType", "()Z"),
    "thread_is_interview_live_style": ("com.baidu.tbadk.core.data.ThreadData", "isInterviewLiveStyle", "()Z"),
    "thread_is_ala_live_user": ("com.baidu.tbadk.core.data.ThreadData", "isAlaLiveUser", "()Z"),
    "ala_info_yy_live": ("com.baidu.tbadk.core.data.AlaInfoData", "getIsYYLiveUi", "()Z"),
    "thread_ala_info": ("com.baidu.tbadk.core.data.ThreadData", "getThreadAlaInfo", "()Lcom/baidu/tbadk/core/data/AlaInfoData;"),
    "em8_get_thread": ("com.baidu.tieba.em8", "getThreadData", "()Lcom/baidu/tbadk/core/data/ThreadData;"),
    "live_adapter_fill": ("com.baidu.tieba.homepage.personalize.adapter.HomePageAlaLiveThreadAdapter", "onFillViewHolder", "(ILandroid/view/View;Landroid/view/ViewGroup;Ljava/lang/Object;Lcom/baidu/adp/widget/ListView/TypeAdapter$ViewHolder;)Landroid/view/View;"),
    "ala_live_card_bind": ("com.baidu.tieba.compact.AlaLiveAttentionCardView", "c", "(Lcom/baidu/tieba/rl8;)V"),
    "live_card_create": ("com.baidu.tieba.ssb", "a", "(Landroid/view/ViewGroup;I)Landroid/view/View;"),
    "live_item_16_set_data": ("com.baidu.live.business.Live16Ratio9ItemView", "setData", "(Lcom/baidu/live/business/model/data/LiveRoomEntity;I)V"),
    "live_item_45_set_data": ("com.baidu.live.business.Live4Ratio5ItemView", "setData", "(Lcom/baidu/live/business/model/data/LiveRoomEntity;I)V"),
    "thread_ad_url": ("com.baidu.tbadk.core.data.ThreadData", "getAd_url", "()Ljava/lang/String;"),
    "thread_ad_subscript": ("com.baidu.tbadk.core.data.ThreadData", "getShowAdSubscript", "()Z"),
    "thread_is_live": ("com.baidu.tbadk.core.data.ThreadData", "isAlaLiveMixThreadType", "()Z"),
    "thread_is_act": ("com.baidu.tbadk.core.data.ThreadData", "isActInfo", "()Z"),

    # 以下 8 个历史锚点已确认在 app/src/main/java 中没有任何
    # anchors.method()/clazz() 引用（删除前全仓库检索只命中 anchors.json 与本文件），
    # 属于死锚点，已从生成清单和 anchors.json 移除：
    #   big_image_loader / save_image / sign_single_y0 / tbwebview_load_url /
    #   thread_is_bjh / thread_is_smartapp / thread_is_ugc / webview_page_finished

    # ---------- phase 2 ----------
    "pid_loader_load": ("com.fun.ad.sdk.internal.api.BasePidLoader", "load", "(Landroid/content/Context;Lcom/fun/ad/sdk/FunAdSlot;Lcom/fun/ad/sdk/internal/api/SidSessionMeta;)Z"),
    "nad_requester_a": ("com.baidu.nadcore.requester.NadRequester", "a", "(Lcom/baidu/nadcore/requester/RequestParameters;Lcom/baidu/nadcore/requester/NadRequester$b;)V"),
    "qy8_c": ("com.baidu.tieba.qy8", "c", "(Lcom/baidu/tieba/c18;Lcom/baidu/tieba/i18;)V"),
    "add_lz": ("com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "addLz", "(Ltbclient/PbList/DataReq$Builder;)V"),
    "add_r": ("com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "addR", "(Ltbclient/PbList/DataReq$Builder;)V"),
    "watermark_type": ("com.baidu.tieba.j76", "b", "()I"),
    "original_button": ("com.baidu.tbadk.coreExtra.view.UrlDragImageView", "W", "()Z"),
    "video_set_path": ("com.baidu.tieba.play.TbVideoView", "setVideoPath", "(Ljava/lang/String;Ljava/lang/String;)V"),
    "sign_all_load": ("com.baidu.tieba.signall.SignAllForumModel", "loadData", "()Z"),

    # ---------- phase 3 ----------
    "clipboard": ("com.baidu.tbadk.core.util.UtilHelper", "getClipBoardContent", "()Ljava/lang/String;"),
    "personalized_ad": ("com.baidu.tbadk.TbSingleton", "getPersonalizedAdSwitch", "()I"),
    "keep_alive": ("com.baidu.tieba.ala.ALaKeepAliveService", "startService", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V"),

    # ---------- ad insertion gates ----------
    "ad_insert_gate": ("com.baidu.tieba.dw7", "n", "(Lcom/baidu/tieba/ad/AbsDataRecorder$Scene;)Z"),
    "ad_card_gate": ("com.baidu.tieba.bie", "h", "(Lcom/baidu/tbadk/core/data/AdvertAppInfo;)Z"),

    # ---------- H5 page hooks ----------
    "person_tab_attach": ("com.baidu.tieba.personTab.controller.PersonTabWebController", "z", "(Landroid/view/View;Landroid/os/Bundle;)V"),
}

# type anchors used for instanceof-style checks
CLASSES = {
    "nt9": "com.baidu.tieba.nt9",
    "advert_app_info": "com.baidu.tbadk.core.data.AdvertAppInfo",
    "thread_data": "com.baidu.tbadk.core.data.ThreadData",
    "lego_ad_card": "com.baidu.tieba.recapp.lego.model.AdCard",
    "lego_ad_data": "com.baidu.tieba.oke",
    "lego_ad_wrapper": "com.baidu.tieba.gje$a",
    "live_thread_wrapper": "com.baidu.tieba.em8",
}


# ---- 工具定位 -------------------------------------------------------------
def _is_executable(path):
    try:
        if not path.is_file():
            return False
    except OSError:
        return False
    if os.name == "nt" or path.suffix.lower() in (".exe", ".bat", ".cmd"):
        return True
    return os.access(str(path), os.X_OK)


def _check_explicit(label, value):
    if not value:
        return None
    path = Path(value).expanduser()
    if _is_executable(path):
        return path
    # 也允许传裸命令名（例如 --dexsql dexsql.exe），最后按 PATH 解析一次。
    which = shutil.which(value)
    if which:
        return Path(which)
    raise SystemExit("ERROR: %s is not executable: %s" % (label, value))


def _version_key(path):
    parts = re.split(r"[._-]", path.name)
    key = []
    for part in parts:
        key.append(int(part) if part.isdigit() else -1)
    return key


def sdk_roots():
    """ANDROID_HOME / ANDROID_SDK_ROOT / local.properties / 常见安装位置。"""
    roots = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            roots.append(Path(value).expanduser())

    local_props = REPO_ROOT / "local.properties"
    if local_props.is_file():
        try:
            text = local_props.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        for line in text.splitlines():
            match = re.match(r"\s*sdk\.dir\s*=\s*(.+?)\s*$", line)
            if match:
                value = match.group(1).replace("\\\\", "\\").replace("\\:", ":")
                roots.append(Path(value).expanduser())

    home = Path.home()
    roots.extend([
        home / "Android" / "Sdk",
        home / "Library" / "Android" / "sdk",
        home / "AppData" / "Local" / "Android" / "Sdk",
        Path("/usr/lib/android-sdk"),
        Path("/opt/android-sdk"),
    ])

    unique = []
    seen = set()
    for root in roots:
        key = str(root)
        if key not in seen:
            seen.add(key)
            unique.append(root)
    return unique


def resolve_dexsql(explicit):
    found = _check_explicit("--dexsql", explicit)
    if found:
        return found
    found = _check_explicit("DEXSQL", os.environ.get("DEXSQL"))
    if found:
        return found

    candidates = [
        shutil.which("dexsql"),
        shutil.which("dexsql.exe"),
        REPO_ROOT / "tools" / "dexsql" / "dexsql.exe",
        REPO_ROOT / "tools" / "dexsql" / "dexsql",
        REPO_ROOT / "tools" / "dexsql.exe",
        Path.home() / "tools" / "dexsql" / "dexsql.exe",
        Path.home() / "tools" / "dexsql" / "dexsql",
        Path.home() / ".local" / "bin" / "dexsql",
        Path("/usr/local/bin/dexsql"),
        Path("/opt/dexsql/dexsql"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate).expanduser()
        if _is_executable(path):
            return path
    raise SystemExit(
        "ERROR: dexsql not found. Pass --dexsql /path/to/dexsql, set DEXSQL, "
        "put it on PATH, or install it under tools/dexsql/."
    )


def resolve_aapt(explicit):
    found = _check_explicit("--aapt2", explicit)
    if found:
        return found
    found = _check_explicit("AAPT2", os.environ.get("AAPT2"))
    if found:
        return found

    which = shutil.which("aapt2") or shutil.which("aapt")
    if which:
        return Path(which)

    for root in sdk_roots():
        build_tools = root / "build-tools"
        if not build_tools.is_dir():
            continue
        try:
            versions = sorted(
                [p for p in build_tools.iterdir() if p.is_dir()],
                key=_version_key, reverse=True,
            )
        except OSError:
            continue
        for version in versions:
            for name in ("aapt2.exe", "aapt2", "aapt.exe", "aapt"):
                candidate = version / name
                if _is_executable(candidate):
                    return candidate
    return None


def resolve_apkanalyzer(explicit):
    found = _check_explicit("--apkanalyzer", explicit)
    if found:
        return found
    found = _check_explicit("APKANALYZER", os.environ.get("APKANALYZER"))
    if found:
        return found

    which = shutil.which("apkanalyzer") or shutil.which("apkanalyzer.bat")
    if which:
        return Path(which)

    for root in sdk_roots():
        cmdline_tools = root / "cmdline-tools"
        if cmdline_tools.is_dir():
            try:
                versions = sorted(
                    [p for p in cmdline_tools.iterdir() if p.is_dir()],
                    key=_version_key, reverse=True,
                )
            except OSError:
                versions = []
            for version in versions:
                for name in ("apkanalyzer", "apkanalyzer.bat"):
                    candidate = version / "bin" / name
                    if _is_executable(candidate):
                        return candidate
        for name in ("apkanalyzer", "apkanalyzer.bat"):
            candidate = root / "tools" / "bin" / name
            if _is_executable(candidate):
                return candidate
    return None


# ---- APK 版本 -------------------------------------------------------------
def _run(cmd):
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
    except OSError:
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def _last_line(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else None


def _apkanalyzer_value(tool, prop, apk):
    # 不同版本的 apkanalyzer 对 --file 的位置要求不同，两种形式都试。
    commands = [
        [str(tool), "manifest", prop, str(apk)],
        [str(tool), "--file", str(apk), "manifest", prop],
    ]
    for cmd in commands:
        output = _run(cmd)
        value = _last_line(output) if output else None
        if value:
            return value
    return None


def read_apk_version(apk, aapt, apkanalyzer):
    name = None
    code = None
    if aapt:
        output = _run([str(aapt), "dump", "badging", str(apk)])
        if output:
            match = re.search(r"versionName='([^']*)'", output)
            if match:
                name = match.group(1)
            match = re.search(r"versionCode='([0-9]+)'", output)
            if match:
                code = match.group(1)
    if apkanalyzer and name is None:
        name = _apkanalyzer_value(apkanalyzer, "version-name", apk)
    if apkanalyzer and code is None:
        code = _apkanalyzer_value(apkanalyzer, "version-code", apk)
    return name, code


# ---- DexSQL ---------------------------------------------------------------
def dexsql(tool, apk, sql):
    result = subprocess.run(
        [str(tool), "query", "--input", str(apk), "--sql", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def table_rows(output):
    """Parse DexSQL's ASCII table; return list of cells per data row."""
    rows = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= {"|", "-", "+"}:
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if cells and not cells[0].lower().startswith(("class_descriptor", "java_name", "count")):
            rows.append(cells)
    return rows


def main():
    parser = argparse.ArgumentParser(
        description="用 DexSQL 校验 hook 锚点并生成 anchors.json。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--apk", required=True, help="目标 APK")
    parser.add_argument("--out", required=True, help="输出 anchors.json")
    parser.add_argument(
        "--dexsql",
        help="dexsql 可执行文件（默认：DEXSQL 环境变量 / PATH / 仓库 tools/dexsql/ / 常见路径）",
    )
    parser.add_argument("--aapt2", help="aapt2/aapt 可执行文件（读取 APK versionName）")
    parser.add_argument("--apkanalyzer", help="apkanalyzer 可执行文件（读取 APK 版本）")
    parser.add_argument(
        "--app-version", default=os.environ.get("APP_VERSION"),
        help="APK 读取失败时显式指定 versionName（建议仅作兜底）",
    )
    parser.add_argument(
        "--version-code", default=os.environ.get("VERSION_CODE"),
        help="APK 读取失败时显式指定 versionCode（建议仅作兜底）",
    )
    args = parser.parse_args()

    apk = Path(args.apk).expanduser()
    if not apk.is_file():
        raise SystemExit("ERROR: APK not found: %s" % apk)

    dexsql_path = resolve_dexsql(args.dexsql)
    aapt = resolve_aapt(args.aapt2)
    apkanalyzer = resolve_apkanalyzer(args.apkanalyzer)

    app_version, version_code = read_apk_version(apk, aapt, apkanalyzer)
    if not app_version:
        if args.app_version:
            app_version = args.app_version
            print(
                "WARN: 无法从 APK 读取 versionName，使用 --app-version=%s" % app_version,
                file=sys.stderr,
            )
        else:
            raise SystemExit(
                "ERROR: 无法从 APK 读取 versionName（aapt2/aapt/apkanalyzer 不可用或失败）；"
                "请安装工具，或显式传 --app-version"
            )
    if not version_code:
        if args.version_code:
            version_code = args.version_code
            print(
                "WARN: 无法从 APK 读取 versionCode，使用 --version-code=%s" % version_code,
                file=sys.stderr,
            )
        else:
            raise SystemExit(
                "ERROR: 无法从 APK 读取 versionCode（aapt2/aapt/apkanalyzer 不可用或失败）；"
                "请安装工具，或显式传 --version-code"
            )
    try:
        version_code_value = int(str(version_code).strip())
    except ValueError:
        raise SystemExit("ERROR: versionCode 不是整数: %r" % version_code)

    print("APK: %s" % apk)
    print("appVersion: %s (versionCode %d)" % (app_version, version_code_value))
    print("dexsql: %s" % dexsql_path)

    try:
        ver_out = dexsql(dexsql_path, apk, "SELECT count(*) AS c FROM classes")
    except RuntimeError as exc:
        raise SystemExit("ERROR: dexsql failed: %s" % exc)
    rows = table_rows(ver_out)
    print("dexsql ok: classes=%s" % (rows[-1][0] if rows else "?"))

    anchors = {}
    missing = []
    for key, (cls, name, sig) in ANCHORS.items():
        full = "L%s;->%s%s" % (cls.replace(".", "/"), name, sig)
        try:
            out = dexsql(
                dexsql_path, apk,
                "SELECT count(*) AS c FROM methods WHERE descriptor='%s'" % full,
            )
        except RuntimeError as exc:
            raise SystemExit("ERROR: dexsql failed for anchor %s: %s" % (key, exc))
        rows = table_rows(out)
        # last row is the value; earlier rows may be headers
        ok = bool(rows) and rows[-1][0].isdigit() and int(rows[-1][0]) > 0
        if ok:
            anchors[key] = {"c": cls, "m": name, "s": sig}
        else:
            missing.append((key, full))
            print("MISSING %s: %s" % (key, full))

    classes = {}
    for key, cls in CLASSES.items():
        try:
            out = dexsql(
                dexsql_path, apk,
                "SELECT count(*) AS c FROM types WHERE java_name='%s'" % cls,
            )
        except RuntimeError as exc:
            raise SystemExit("ERROR: dexsql failed for class %s: %s" % (key, exc))
        rows = table_rows(out)
        ok = bool(rows) and rows[-1][0].isdigit() and int(rows[-1][0]) > 0
        if ok:
            classes[key] = cls
        else:
            missing.append((key, cls))
            print("MISSING class %s: %s" % (key, cls))

    out_path = Path(args.out).expanduser()
    if missing:
        print(
            "ERROR: %d 个锚点/类缺失，未写入 %s（保留原文件）"
            % (len(missing), out_path),
            file=sys.stderr,
        )
        return 1

    payload = {
        "appVersion": app_version,
        "versionCode": version_code_value,
        "generatedBy": "tools/gen_anchors.py",
        "anchors": anchors,
        "classes": classes,
    }
    try:
        out_path.parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise SystemExit("ERROR: cannot create output dir %s: %s" % (out_path.parent, exc))

    # 先写同目录临时文件，全部成功后再 rename，避免半成品覆盖旧 anchors.json。
    tmp_path = None
    try:
        fd, tmp_name = tempfile.mkstemp(
            prefix=out_path.name + ".", suffix=".tmp", dir=str(out_path.parent),
        )
        tmp_path = Path(tmp_name)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        # mkstemp 默认 0600；沿用旧文件权限（不存在则 0644），避免输出权限突变。
        try:
            mode = out_path.stat().st_mode & 0o777 if out_path.exists() else 0o644
            os.chmod(str(tmp_path), mode)
        except OSError:
            pass
        os.replace(str(tmp_path), str(out_path))
        tmp_path = None
    except OSError as exc:
        raise SystemExit("ERROR: cannot write %s: %s" % (out_path, exc))
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink()
            except OSError:
                pass

    print("wrote %s (%d methods, %d classes)" % (out_path, len(anchors), len(classes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
