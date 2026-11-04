#!/usr/bin/env python3
"""
生成 anchors.json：用 DexSQL 校验每个 hook 锚点在目标 APK 中存在，只输出命中项。

用法:
    python tools/gen_anchors.py --apk /path/to/tieba.apk \
        --out app/src/main/resources/anchors.json \
        --keys-out app/src/main/java/io/github/yigenhuobah/tiebaquiet/anchor/AnchorKeys.java

    # 找不到 dexsql 时显式指定
    python tools/gen_anchors.py --apk ... --out ... --dexsql /path/to/dexsql

输出 schema v1：顶层 schemaVersion/appVersion/versionCode/generatedBy/anchors/classes；
每个锚点 c/m/s + feature/critical/note。s 以 "(" 开头是方法，否则是字段类型描述符。
--keys-out 省略时按 --out 的标准 app/src/main/resources 布局推导 AnchorKeys.java；
推导不到则只写 anchors.json。

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

# 锚点单一事实来源。
#
# key -> {c: 目标类 FQCN, m: 方法名或字段名, s: JVM 描述符,
#         feature: 功能编号（AD/UI/PV/EN-NN）, critical: 缺失是否直接导致功能不可用,
#         note: 适配备注}
#
# s 以 "(" 开头表示方法（methods 表校验）；否则表示字段类型描述符
# （fields 表校验，如 "Ljava/lang/String;"）。字段锚点用于混名前无法改名/改类型的
# 宿主字段，运行时由 AnchorTable.field(key) 解析。
ANCHORS = {
    # ---------- phase 1 ----------
    "splash_forbid_b": {
        "c": "com.baidu.tieba.ad.under.utils.SplashForbidAdHelperKt", "m": "b", "s": "()Z",
        "feature": "AD-01", "critical": True, "note": "开屏广告，走官方会员无开屏分支",
    },
    "yun_dialog_can_show": {
        "c": "com.baidu.tbadk.core.dialog.yun.YunDialogManager", "m": "canShowDialog", "s": "(Lcom/baidu/tieba/od6;)Z",
        "feature": "AD-08", "critical": True, "note": "营销/活动/签到弹窗总闸",
    },
    "home_ad_float": {
        "c": "com.baidu.tbadk.TbSingleton", "m": "getHomeAdFloatViewItemData", "s": "()Lcom/baidu/tieba/po6;",
        "feature": "AD-07", "critical": True, "note": "首页悬浮广告数据",
    },
    "pb_ad_float": {
        "c": "com.baidu.tbadk.TbSingleton", "m": "getPbAdFloatViewItemData", "s": "()Lcom/baidu/tieba/po6;",
        "feature": "AD-07", "critical": True, "note": "帖子页悬浮广告数据",
    },
    "template_set_list": {
        "c": "com.baidu.tieba.feed.list.TemplateAdapter", "m": "setList", "s": "(Ljava/util/List;)V",
        "feature": "AD-03", "critical": True, "note": "信息流统一入口，AD-03/AD-05/AD-06 共用",
    },
    "tab_add_tip": {
        "c": "com.baidu.tbadk.mainTab.TbFragmentTabIndicator", "m": "addTip", "s": "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V",
        "feature": "UI-01", "critical": True, "note": "底栏红点主入口",
    },
    "tab_add_tip_2": {
        "c": "com.baidu.tbadk.mainTab.FragmentTabIndicator", "m": "addTip", "s": "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V",
        "feature": "UI-01", "critical": False, "note": "底栏红点兼容入口 2",
    },
    "tab_add_tip_3": {
        "c": "com.baidu.tbadk.mainTab.MainTabBottomOptIndicator", "m": "addTip", "s": "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V",
        "feature": "UI-01", "critical": False, "note": "底栏红点兼容入口 3",
    },
    "tab_add_tip_4": {
        "c": "com.baidu.tbadk.mainTab.MaintabBottomIndicator", "m": "addTip", "s": "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V",
        "feature": "UI-01", "critical": False, "note": "底栏红点兼容入口 4",
    },
    "tab_add_tip_wrap": {
        "c": "com.baidu.tbadk.mainTab.TbFragmentTabIndicator", "m": "addTipWrapContent", "s": "(Ljava/lang/String;Lcom/baidu/tbadk/mainTab/TbFragmentTabIndicator$FragmentTapTip;)V",
        "feature": "UI-01", "critical": False, "note": "底栏红点自适应入口",
    },
    "msg_red_dot_refresh": {
        "c": "com.baidu.tbadk.core.view.MessageRedDotView", "m": "refresh", "s": "(I)V",
        "feature": "UI-01", "critical": True, "note": "消息红点 int 入口",
    },
    "msg_red_dot_refresh_opt": {
        "c": "com.baidu.tbadk.core.view.MessageRedDotView", "m": "refreshFeedUIOpt", "s": "(I)V",
        "feature": "UI-01", "critical": False, "note": "信息流红点刷新",
    },
    "msg_red_dot_refresh_str": {
        "c": "com.baidu.tbadk.core.view.MessageRedDotView", "m": "refresh", "s": "(Ljava/lang/String;Z)V",
        "feature": "UI-01", "critical": False, "note": "消息红点字符串入口",
    },
    "rec_forum_strategy_h": {
        "c": "com.baidu.tieba.forum.strategy.RecommendForumShowStrategy", "m": "h", "s": "(Lcom/baidu/tieba/i8a;)Z",
        "feature": "AD-09", "critical": True, "note": "推荐吧卡展示策略",
    },
    "upgrade_pop": {
        "c": "com.baidu.tbadk.core.util.UpgradePopWindowHelper", "m": "showPopWindow", "s": "(Lcom/baidu/tbadk/TbPageContext;Lcom/baidu/tieba/yp6;)V",
        "feature": "UI-02", "critical": True, "note": "更新弹窗",
    },
    "easter_egg_o": {
        "c": "com.baidu.tieba.easteregg.EasterEggAdController", "m": "o", "s": "(Lcom/baidu/tieba/easteregg/data/EasterEggAdData;)V",
        "feature": "AD-10", "critical": True, "note": "彩蛋广告",
    },
    "new_scroll_r": {
        "c": "com.baidu.tieba.homepage.framework.indicator.NewScrollFragmentAdapter", "m": "r", "s": "(Landroid/content/Context;Ljava/util/List;)V",
        "feature": "EN-01", "critical": True, "note": "频道栏顺序装配入口",
    },
    "skin_type": {
        "c": "com.baidu.tbadk.core.TbadkCoreApplication", "m": "getSkinType", "s": "()I",
        "feature": "EN-02", "critical": True, "note": "皮肤/夜间模式类型",
    },
    "forum_res_parser_a": {
        "c": "com.baidu.tieba.forum.data.protobuf.BaseForumResParser", "m": "a", "s": "(Ljava/lang/Object;Lcom/baidu/tieba/vj9;Ljava/util/Map;ZLjava/util/Map;)Ljava/util/List;",
        "feature": "AD-04", "critical": True, "note": "吧页推广卡解析结果",
    },

    # ---------- reflection helpers (filtering) ----------
    # 22.13.1.1 重定位：nt9→eu9（接口结构 b()/d() 不变，62 个实现类；旧名被无关类复用）。
    "nt9_d": {
        "c": "com.baidu.tieba.eu9", "m": "d", "s": "()Ljava/lang/Object;",
        "feature": "AD-03", "critical": True, "note": "信息流条目取数据对象",
    },
    "nt9_b": {
        "c": "com.baidu.tieba.eu9", "m": "b", "s": "()Ljava/lang/String;",
        "feature": "AD-03", "critical": False, "note": "信息流条目类型键（直播卡识别）",
    },
    "thread_get_is_live": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "getIsLive", "s": "()I",
        "feature": "AD-05", "critical": False, "note": "直播状态整型入口",
    },
    "thread_is_interview_live": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isInterviewLive", "s": "()Z",
        "feature": "AD-05", "critical": True, "note": "直播访谈卡判定",
    },
    "thread_is_live_thread": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isLiveThread", "s": "()Z",
        "feature": "AD-05", "critical": False, "note": "直播帖判定 1",
    },
    "thread_is_live_interview_type": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isLiveInterviewLiveType", "s": "()Z",
        "feature": "AD-05", "critical": False, "note": "直播帖判定 2",
    },
    "thread_is_interview_live_style": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isInterviewLiveStyle", "s": "()Z",
        "feature": "AD-05", "critical": False, "note": "直播帖判定 3",
    },
    "thread_is_ala_live_user": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isAlaLiveUser", "s": "()Z",
        "feature": "AD-05", "critical": False, "note": "主播直播帖判定",
    },
    "ala_info_yy_live": {
        "c": "com.baidu.tbadk.core.data.AlaInfoData", "m": "getIsYYLiveUi", "s": "()Z",
        "feature": "AD-05", "critical": False, "note": "YY 直播 UI 判定",
    },
    "thread_ala_info": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "getThreadAlaInfo", "s": "()Lcom/baidu/tbadk/core/data/AlaInfoData;",
        "feature": "AD-05", "critical": True, "note": "直播附加信息",
    },
    # 22.13.1.1 重定位：em8→tm8（HomePageAlaLiveThreadAdapter 调用方证实，父类 fm8→um8）。
    "em8_get_thread": {
        "c": "com.baidu.tieba.tm8", "m": "getThreadData", "s": "()Lcom/baidu/tbadk/core/data/ThreadData;",
        "feature": "AD-05", "critical": True, "note": "直播包装类取 ThreadData",
    },
    "live_adapter_fill": {
        "c": "com.baidu.tieba.homepage.personalize.adapter.HomePageAlaLiveThreadAdapter", "m": "onFillViewHolder", "s": "(ILandroid/view/View;Landroid/view/ViewGroup;Ljava/lang/Object;Lcom/baidu/adp/widget/ListView/TypeAdapter$ViewHolder;)Landroid/view/View;",
        "feature": "AD-05", "critical": False, "note": "首页直播卡兜底适配器",
    },
    # 22.13.1.1 重定位：绑定方法 c(rl8)→b(gm8)（调用方 ko8.e→zo8.e 拓扑一致）；
    # 适配器 ssb→nsb（方法集 a-k 逐一同构）。
    "ala_live_card_bind": {
        "c": "com.baidu.tieba.compact.AlaLiveAttentionCardView", "m": "b", "s": "(Lcom/baidu/tieba/gm8;)V",
        "feature": "AD-05", "critical": True, "note": "关注主播直播卡绑定",
    },
    "live_card_create": {
        "c": "com.baidu.tieba.nsb", "m": "a", "s": "(Landroid/view/ViewGroup;I)Landroid/view/View;",
        "feature": "AD-05", "critical": True, "note": "直播推荐卡创建",
    },
    "live_item_16_set_data": {
        "c": "com.baidu.live.business.Live16Ratio9ItemView", "m": "setData", "s": "(Lcom/baidu/live/business/model/data/LiveRoomEntity;I)V",
        "feature": "AD-05", "critical": True, "note": "16:9 直播 item 绑定",
    },
    "live_item_45_set_data": {
        "c": "com.baidu.live.business.Live4Ratio5ItemView", "m": "setData", "s": "(Lcom/baidu/live/business/model/data/LiveRoomEntity;I)V",
        "feature": "AD-05", "critical": False, "note": "4:5 直播 item 绑定",
    },
    "thread_ad_url": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "getAd_url", "s": "()Ljava/lang/String;",
        "feature": "AD-03", "critical": True, "note": "帖子广告 URL 判定",
    },
    "thread_ad_subscript": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "getShowAdSubscript", "s": "()Z",
        "feature": "AD-03", "critical": True, "note": "帖子广告角标判定",
    },
    "thread_is_live": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isAlaLiveMixThreadType", "s": "()Z",
        "feature": "AD-05", "critical": True, "note": "混合信息流直播帖判定",
    },
    "thread_is_act": {
        "c": "com.baidu.tbadk.core.data.ThreadData", "m": "isActInfo", "s": "()Z",
        "feature": "AD-03", "critical": True, "note": "活动卡判定",
    },

    # 以下 8 个历史锚点已确认在 app/src/main/java 中没有任何
    # anchors.method()/clazz() 引用（删除前全仓库检索只命中 anchors.json 与本文件），
    # 属于死锚点，已从生成清单和 anchors.json 移除：
    #   big_image_loader / save_image / sign_single_y0 / tbwebview_load_url /
    #   thread_is_bjh / thread_is_smartapp / thread_is_ugc / webview_page_finished

    # ---------- phase 2 ----------
    "pid_loader_load": {
        "c": "com.fun.ad.sdk.internal.api.BasePidLoader", "m": "load", "s": "(Landroid/content/Context;Lcom/fun/ad/sdk/FunAdSlot;Lcom/fun/ad/sdk/internal/api/SidSessionMeta;)Z",
        "feature": "AD-02", "critical": True, "note": "FunAd/ADX 请求源头",
    },
    "nad_requester_a": {
        "c": "com.baidu.nadcore.requester.NadRequester", "m": "a", "s": "(Lcom/baidu/nadcore/requester/RequestParameters;Lcom/baidu/nadcore/requester/NadRequester$b;)V",
        "feature": "AD-02", "critical": True, "note": "聚屏/插屏/激励请求入口",
    },
    "nad_callback_a": {
        "c": "com.baidu.nadcore.requester.NadRequester$b", "m": "a", "s": "(Lcom/baidu/nadcore/requester/NadRequester$Error;)V",
        "feature": "AD-02", "critical": False, "note": "Nad 失败回调，用于回填 Blocked by client",
    },
    # 22.13.1.1 重定位：qy8→fz8（c() 内 getFeedAd URL、d() 返回 asyncAfd 双指纹），
    # c18→r18、i18→x18（接口 a/b/c 形状不变）。
    "qy8_c": {
        "c": "com.baidu.tieba.fz8", "m": "c", "s": "(Lcom/baidu/tieba/r18;Lcom/baidu/tieba/x18;)V",
        "feature": "AD-02", "critical": True, "note": "贴吧自研异步 AFD 请求",
    },
    "feed_callback_c": {
        "c": "com.baidu.tieba.x18", "m": "c", "s": "(Lcom/baidu/tieba/r18;ILjava/lang/String;)V",
        "feature": "AD-02", "critical": False, "note": "自研 AFD 失败回调",
    },
    "add_lz": {
        "c": "com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "m": "addLz", "s": "(Ltbclient/PbList/DataReq$Builder;)V",
        "feature": "EN-03", "critical": True, "note": "只看楼主：强制 lz=1",
    },
    "add_r": {
        "c": "com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage", "m": "addR", "s": "(Ltbclient/PbList/DataReq$Builder;)V",
        "feature": "EN-04", "critical": True, "note": "楼层倒序：强制 r=1",
    },
    "watermark_type": {
        "c": "com.baidu.tieba.j76", "m": "b", "s": "()I",
        "feature": "EN-05", "critical": True, "note": "图片水印类型",
    },
    "original_button": {
        "c": "com.baidu.tbadk.coreExtra.view.UrlDragImageView", "m": "W", "s": "()Z",
        "feature": "EN-06", "critical": True, "note": "查看原图按钮可用性",
    },
    "video_set_path": {
        "c": "com.baidu.tieba.play.TbVideoView", "m": "setVideoPath", "s": "(Ljava/lang/String;Ljava/lang/String;)V",
        "feature": "EN-07", "critical": True, "note": "视频直链复制",
    },
    "sign_all_load": {
        "c": "com.baidu.tieba.signall.SignAllForumModel", "m": "loadData", "s": "()Z",
        "feature": "EN-08", "critical": True, "note": "一键签到可用性门禁",
    },
    "retail_store_page": {
        "c": "com.baidu.tbadk.abtest.UbsABTestHelper", "m": "retailStorePage", "s": "()Z",
        "feature": "UI-04", "critical": True, "note": "底栏小卖部 Tab 开关",
    },

    # ---------- 频道栏 Tab 字段（字段锚点：s 为字段类型，不含括号） ----------
    "home_tab_item_b": {
        "c": "com.baidu.tieba.homepage.framework.indicator.NewScrollFragmentAdapter$b", "m": "b", "s": "Ljava/lang/String;",
        "feature": "EN-01", "critical": True, "note": "频道 Tab 名称字段，用于识别“关注”",
    },
    "home_tab_item_c": {
        "c": "com.baidu.tieba.homepage.framework.indicator.NewScrollFragmentAdapter$b", "m": "c", "s": "Ljava/lang/String;",
        "feature": "EN-01", "critical": False, "note": "频道 Tab 备用名称字段",
    },

    # ---------- phase 3 ----------
    "clipboard": {
        "c": "com.baidu.tbadk.core.util.UtilHelper", "m": "getClipBoardContent", "s": "()Ljava/lang/String;",
        "feature": "PV-01", "critical": True, "note": "剪贴板读取拦截",
    },
    "personalized_ad": {
        "c": "com.baidu.tbadk.TbSingleton", "m": "getPersonalizedAdSwitch", "s": "()I",
        "feature": "PV-02", "critical": True, "note": "个性化广告开关",
    },
    "keep_alive": {
        "c": "com.baidu.tieba.ala.ALaKeepAliveService", "m": "startService", "s": "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
        "feature": "PV-03", "critical": True, "note": "ALa 保活服务拉起",
    },

    # ---------- ad insertion gates ----------
    # 22.13.1.1 重定位：dw7→qw7（FrsFeedAdStatic.e 等调用方拓扑一致）；
    # bie→ike（方法表/字符串常量/调用方三方同构）。
    "ad_insert_gate": {
        "c": "com.baidu.tieba.qw7", "m": "n", "s": "(Lcom/baidu/tieba/ad/AbsDataRecorder$Scene;)Z",
        "feature": "AD-02", "critical": True, "note": "信息流广告插入闸门",
    },
    "ad_card_gate": {
        "c": "com.baidu.tieba.ike", "m": "h", "s": "(Lcom/baidu/tbadk/core/data/AdvertAppInfo;)Z",
        "feature": "AD-02", "critical": True, "note": "推广 App 单卡展示闸门",
    },

    # ---------- H5 page hooks ----------
    "person_tab_attach": {
        "c": "com.baidu.tieba.personTab.controller.PersonTabWebController", "m": "z", "s": "(Landroid/view/View;Landroid/os/Bundle;)V",
        "feature": "UI-03", "critical": True, "note": "个人页 H5 注入时机",
    },
    # 22.13.1.1：UI-03 个人页注入链 k()/getInnerWebView。此前是 Phase1Features 里的
    # 硬编码反射名（改名/改可见性即静默失效），已收编进锚点清单；签名用 dex 方法表
    # 直接核实（classes11.dex：Lcom/baidu/tieba/personTab/controller/PersonTabWebController;
    # ->k()Lcom/baidu/tieba/browser/TbWebView; 与
    # Lcom/baidu/tieba/browser/TbWebView;->getInnerWebView()Landroid/webkit/WebView;）。
    "person_tab_web_k": {
        "c": "com.baidu.tieba.personTab.controller.PersonTabWebController", "m": "k", "s": "()Lcom/baidu/tieba/browser/TbWebView;",
        "feature": "UI-03", "critical": True, "note": "个人页控制器取 TbWebView",
    },
    "tbwebview_inner": {
        "c": "com.baidu.tieba.browser.TbWebView", "m": "getInnerWebView", "s": "()Landroid/webkit/WebView;",
        "feature": "UI-03", "critical": True, "note": "TbWebView 取内嵌 WebView",
    },
}

# type anchors used for instanceof-style checks / 反射入口类
CLASSES = {
    # 22.13.1.1 重定位：nt9→eu9、em8→tm8；gje$a→nle$a（lego 适配器 gje→nle，
    # 包装类字段 AdvertAppInfo/I/String 逐一对应，fcc→acc）。
    "nt9": "com.baidu.tieba.eu9",
    "advert_app_info": "com.baidu.tbadk.core.data.AdvertAppInfo",
    "thread_data": "com.baidu.tbadk.core.data.ThreadData",
    "lego_ad_card": "com.baidu.tieba.recapp.lego.model.AdCard",
    "lego_ad_data": "com.baidu.tieba.oke",
    "lego_ad_wrapper": "com.baidu.tieba.nle$a",
    "live_thread_wrapper": "com.baidu.tieba.tm8",
    # 广告请求断源回调链（原先硬编码在 Phase2Features）。
    "nad_error": "com.baidu.nadcore.requester.NadRequester$Error",
    "nad_callback": "com.baidu.nadcore.requester.NadRequester$b",
    "feed_request": "com.baidu.tieba.r18",
    "feed_callback": "com.baidu.tieba.x18",
    # 一键签到目标页（原先硬编码在 SignInFeature）。
    "sign_all_activity": "com.baidu.tieba.signall.SignAllForumActivity",
    # 频道栏 Tab 数据类（字段锚点宿主，原先硬编码在 Phase1Features）。
    "home_tab_item": "com.baidu.tieba.homepage.framework.indicator.NewScrollFragmentAdapter$b",
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


# ---- AnchorKeys.java ------------------------------------------------------
def java_constant_name(key):
    """锚点 key → Java 常量名（大写、非字母数字转下划线）。"""
    return re.sub(r"[^0-9A-Za-z]+", "_", key).upper()


def anchor_keys_source(anchor_keys, class_keys):
    lines = [
        "// Generated by tools/gen_anchors.py from anchors.json. DO NOT EDIT.",
        "package io.github.yigenhuobah.tiebaquiet.anchor;",
        "",
        "/**",
        " * anchors.json 的逻辑键：编译期常量，避免 hook 代码里散落裸字符串。",
        " *",
        " * <p>由 tools/gen_anchors.py 生成；新增/删除锚点后重新生成，",
        " * AnchorContractTest 会校验常量与 anchors.json 双向一致。",
        " */",
        "public final class AnchorKeys {",
        "",
        "    private AnchorKeys() {",
        "    }",
        "",
    ]
    seen = {}
    for key in list(anchor_keys) + list(class_keys):
        constant = java_constant_name(key)
        if not constant or constant[0].isdigit():
            raise SystemExit("ERROR: 无法从锚点 key 生成合法 Java 常量名: %r" % key)
        if constant in seen and seen[constant] != key:
            raise SystemExit(
                "ERROR: 锚点 key %r 与 %r 生成同名常量 %s"
                % (seen[constant], key, constant)
            )
        if constant in seen:
            continue
        seen[constant] = key
        lines.append('    public static final String %s = "%s";' % (constant, key))
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def default_keys_path(out_path):
    """--out 位于标准 app/src/main/resources 布局时推导 AnchorKeys.java 路径。"""
    out = out_path.resolve()
    try:
        out.relative_to(REPO_ROOT)
    except ValueError:
        return None
    if out.name != "anchors.json" or out.parent.name != "resources" \
            or out.parent.parent.name != "main":
        return None
    return (REPO_ROOT / "app" / "src" / "main" / "java" / "io" / "github"
            / "yigenhuobah" / "tiebaquiet" / "anchor" / "AnchorKeys.java")


def write_text_atomic(path, text):
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise SystemExit("ERROR: cannot create output dir %s: %s" % (path.parent, exc))

    tmp_path = None
    try:
        fd, tmp_name = tempfile.mkstemp(
            prefix=path.name + ".", suffix=".tmp", dir=str(path.parent),
        )
        tmp_path = Path(tmp_name)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        # mkstemp 默认 0600；沿用旧文件权限（不存在则 0644），避免输出权限突变。
        try:
            mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
            os.chmod(str(tmp_path), mode)
        except OSError:
            pass
        os.replace(str(tmp_path), str(path))
        tmp_path = None
    except OSError as exc:
        raise SystemExit("ERROR: cannot write %s: %s" % (path, exc))
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink()
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(
        description="用 DexSQL 校验 hook 锚点并生成 anchors.json。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--apk", required=True, help="目标 APK")
    parser.add_argument("--out", required=True, help="输出 anchors.json")
    parser.add_argument(
        "--keys-out",
        help="输出 AnchorKeys.java（默认按 --out 的标准布局推导；推导不到则不生成）",
    )
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
    for key, meta in ANCHORS.items():
        for field in ("c", "m", "s", "feature", "note"):
            value = meta.get(field)
            if not isinstance(value, str) or not value:
                raise SystemExit("ERROR: anchor %s 缺少字符串字段 %s" % (key, field))
        if not isinstance(meta.get("critical"), bool):
            raise SystemExit("ERROR: anchor %s 的 critical 必须是布尔值" % key)
        cls, name, sig = meta["c"], meta["m"], meta["s"]
        # s 以 "(" 开头是方法描述符；否则是字段类型描述符（字段锚点）。
        # methods 表描述符为 "Lcls;->name(sig)"，fields 表为 "Lcls;->name:type"。
        if sig.startswith("("):
            table = "methods"
            full = "L%s;->%s%s" % (cls.replace(".", "/"), name, sig)
        else:
            table = "fields"
            full = "L%s;->%s:%s" % (cls.replace(".", "/"), name, sig)
        try:
            out = dexsql(
                dexsql_path, apk,
                "SELECT count(*) AS c FROM %s WHERE descriptor='%s'" % (table, full),
            )
        except RuntimeError as exc:
            raise SystemExit("ERROR: dexsql failed for anchor %s: %s" % (key, exc))
        rows = table_rows(out)
        # last row is the value; earlier rows may be headers
        ok = bool(rows) and rows[-1][0].isdigit() and int(rows[-1][0]) > 0
        if ok:
            anchors[key] = {
                "c": cls,
                "m": name,
                "s": sig,
                "feature": meta["feature"],
                "critical": meta["critical"],
                "note": meta["note"],
            }
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
        "schemaVersion": 1,
        "appVersion": app_version,
        "versionCode": version_code_value,
        "generatedBy": "tools/gen_anchors.py",
        "anchors": anchors,
        "classes": classes,
    }

    keys_path = Path(args.keys_out).expanduser() if args.keys_out else default_keys_path(out_path)
    # 先写临时文件、成功后再 rename，避免半成品覆盖旧 anchors.json。
    write_text_atomic(
        out_path,
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    )
    if keys_path is not None:
        write_text_atomic(
            keys_path,
            anchor_keys_source(list(anchors), list(classes)),
        )
        print("wrote %s (%d keys)" % (keys_path, len(anchors) + len(classes)))

    print("wrote %s (%d anchors, %d classes)" % (out_path, len(anchors), len(classes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
