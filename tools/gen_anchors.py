#!/usr/bin/env python3
"""
生成 anchors.json：用 DexSQL 校验每个 hook 锚点在目标 APK 中存在，只输出命中项。

用法:
    python tools/gen_anchors.py --apk "E:/download/base(3).apk" \
        --out app/src/main/resources/anchors.json
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

DEXSQL = r"E:\tools\dexsql\dexsql.exe"

# key: (class java name, method name, JVM descriptor of parameters+return, kind)
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
    "thread_is_bjh": ("com.baidu.tbadk.core.data.ThreadData", "isBjh", "()Z"),
    "thread_is_smartapp": ("com.baidu.tbadk.core.data.ThreadData", "isSmartAppThreadType", "()Z"),
    "thread_is_live": ("com.baidu.tbadk.core.data.ThreadData", "isAlaLiveMixThreadType", "()Z"),
    "thread_is_act": ("com.baidu.tbadk.core.data.ThreadData", "isActInfo", "()Z"),
    "thread_is_ugc": ("com.baidu.tbadk.core.data.ThreadData", "isUgcThreadType", "()Z"),

    # ---------- phase 2 ----------
    "pid_loader_load": ("com.fun.ad.sdk.internal.api.BasePidLoader", "load", "(Landroid/content/Context;Lcom/fun/ad/sdk/FunAdSlot;Lcom/fun/ad/sdk/internal/api/SidSessionMeta;)Z"),
    "nad_requester_a": ("com.baidu.nadcore.requester.NadRequester", "a", "(Lcom/baidu/nadcore/requester/RequestParameters;Lcom/baidu/nadcore/requester/NadRequester$b;)V"),
    "qy8_c": ("com.baidu.tieba.qy8", "c", "(Lcom/baidu/tieba/c18;Lcom/baidu/tieba/i18;)V"),
    "add_lz": ("com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "addLz", "(Ltbclient/PbList/DataReq$Builder;)V"),
    "add_r": ("com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "addR", "(Ltbclient/PbList/DataReq$Builder;)V"),
    "big_image_loader": ("com.baidu.tbadk.core.util.resourceLoaderProc.BigImageLoaderProc", "getFromRemote", "(Ljava/lang/String;Ljava/lang/String;IILcom/baidu/tieba/q8;[Ljava/lang/Object;)Ljava/lang/Object;"),
    "save_image": ("com.baidu.tbadk.core.util.FileHelper", "saveImageFileByUser", "(Ljava/lang/String;[BLandroid/content/Context;)I"),
    "watermark_type": ("com.baidu.tieba.j76", "b", "()I"),
    "original_button": ("com.baidu.tbadk.coreExtra.view.UrlDragImageView", "W", "()Z"),
    "video_set_path": ("com.baidu.tieba.play.TbVideoView", "setVideoPath", "(Ljava/lang/String;Ljava/lang/String;)V"),
    "sign_all_load": ("com.baidu.tieba.signall.SignAllForumModel", "loadData", "()Z"),
    "sign_single_y0": ("com.baidu.tieba.signall.SignSingleModel", "Y0", "(Ljava/lang/String;Ljava/lang/String;)V"),

    # ---------- phase 3 ----------
    "clipboard": ("com.baidu.tbadk.core.util.UtilHelper", "getClipBoardContent", "()Ljava/lang/String;"),    "personalized_ad": ("com.baidu.tbadk.TbSingleton", "getPersonalizedAdSwitch", "()I"),
    "keep_alive": ("com.baidu.tieba.ala.ALaKeepAliveService", "startService", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V"),

    # ---------- ad insertion gates ----------
    "ad_insert_gate": ("com.baidu.tieba.dw7", "n", "(Lcom/baidu/tieba/ad/AbsDataRecorder$Scene;)Z"),
    "ad_card_gate": ("com.baidu.tieba.bie", "h", "(Lcom/baidu/tbadk/core/data/AdvertAppInfo;)Z"),

    # ---------- H5 page hooks ----------
    "webview_page_finished": ("com.baidu.webkit.sdk.system.WebViewClientWrapper", "onPageFinished", "(Landroid/webkit/WebView;Ljava/lang/String;)V"),
    "tbwebview_load_url": ("com.baidu.tieba.browser.TbWebView", "loadUrl", "(Ljava/lang/String;)V"),
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


def dexsql(apk: str, sql: str) -> str:
    r = subprocess.run(
        [DEXSQL, "query", "--input", apk, "--sql", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or r.stdout.strip())
    return r.stdout


def table_rows(output: str):
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # app version
    ver_out = dexsql(args.apk, "SELECT count(*) FROM classes")
    print("dexsql ok:", ver_out.strip().splitlines()[-2].strip("| ") if ver_out else "?")

    anchors = {}
    missing = []
    for key, (cls, name, sig) in ANCHORS.items():
        full = f"L{cls.replace('.', '/')};->{name}{sig}"
        out = dexsql(args.apk, f"SELECT count(*) AS c FROM methods WHERE descriptor='{full}'")
        rows = table_rows(out)
        # last row is the value; earlier rows may be headers
        ok = bool(rows) and rows[-1][0].isdigit() and int(rows[-1][0]) > 0
        if ok:
            anchors[key] = {"c": cls, "m": name, "s": sig}
        else:
            missing.append((key, full))
            print(f"MISSING {key}: {full}")

    classes = {}
    for key, cls in CLASSES.items():
        out = dexsql(args.apk, f"SELECT count(*) AS c FROM types WHERE java_name='{cls}'")
        rows = table_rows(out)
        ok = bool(rows) and rows[-1][0].isdigit() and int(rows[-1][0]) > 0
        if ok:
            classes[key] = cls
        else:
            missing.append((key, cls))
            print(f"MISSING class {key}: {cls}")

    payload = {
        "appVersion": "22.12.1.0",
        "versionCode": 369885440,
        "generatedBy": "tools/gen_anchors.py",
        "anchors": anchors,
        "classes": classes,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {out} ({len(anchors)} methods, {len(classes)} classes, {len(missing)} missing)")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
