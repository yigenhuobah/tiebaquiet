package io.github.yigenhuobah.tiebaquiet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 关于页：模块/版本、目标应用、源码、四模块互链、免责声明、许可证。
 *
 * <p>互链使用 SpannableString + ClickableSpan + LinkMovementMethod，
 * 颜色取 {@code about_link}；当前模块名称后加“（当前）”。
 */
public class AboutFragment extends PreferenceFragmentCompat {

    private static final String CURRENT_APPLICATION_ID = "io.github.yigenhuobah.tiebaquiet";
    private static final String SOURCE_URL = "https://github.com/yigenhuobah/tiebaquiet";
    private static final String LICENSE_URL = SOURCE_URL + "/blob/main/LICENSE";
    private static final String KEY_VERSION = "about_version";

    /**
     * 版本号查询放后台单线程；结果进程内缓存，反复进入关于页不再重复
     * PackageManager IPC，主线程也不做同步 binder。
     */
    private static final ExecutorService BG_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tiebaquiet-about");
                t.setDaemon(true);
                return t;
            });
    private static final Object VERSION_LOCK = new Object();
    private static boolean sVersionLoaded;
    private static String sVersionName = "?";
    private static int sVersionCode;

    private Future<?> mVersionFuture;

    private static final int[] LINK_NAME_RES = {
            R.string.about_module_cloudmusictool,
            R.string.about_module_quietbox,
            R.string.about_module_quietmap,
            R.string.about_module_tiebaquiet
    };
    private static final int[] LINK_NAME_EN_RES = {
            R.string.about_module_cloudmusictool_en,
            R.string.about_module_quietbox_en,
            R.string.about_module_quietmap_en,
            R.string.about_module_tiebaquiet_en
    };
    private static final String[] LINK_PACKAGES = {
            "io.github.yigenhuobah.cloudmusictool",
            "io.github.yigenhuobah.quietbox",
            "io.github.yigenhuobah.quietmap",
            "io.github.yigenhuobah.tiebaquiet"
    };
    private static final String[] LINK_URLS = {
            "https://github.com/yigenhuobah/cloudmusictool",
            "https://github.com/yigenhuobah/quietbox",
            "https://github.com/yigenhuobah/quietmap",
            "https://github.com/yigenhuobah/tiebaquiet"
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        Preference version = info(R.string.app_name, versionSummary());
        version.setKey(KEY_VERSION);
        screen.addPreference(version);
        loadVersionIfNeeded();

        screen.addPreference(info(R.string.about_target,
                getString(R.string.about_target_value, getString(R.string.app_target_name),
                        ConfigStore.TARGET_PACKAGE, ConfigStore.TARGET_VERSION)));

        Preference source = info(R.string.about_source,
                getString(R.string.about_source_summary, SOURCE_URL));
        source.setOnPreferenceClickListener(p -> {
            openUrl(SOURCE_URL);
            return true;
        });
        screen.addPreference(source);

        screen.addPreference(buildLinksPreference());

        Preference disclaimer = info(R.string.about_disclaimer_title,
                getString(R.string.about_disclaimer, getString(R.string.app_target_name)));
        disclaimer.setSelectable(false);
        screen.addPreference(disclaimer);

        Preference license = info(R.string.about_license_title,
                getString(R.string.about_license_summary, "MIT"));
        license.setOnPreferenceClickListener(p -> {
            openUrl(LICENSE_URL);
            return true;
        });
        screen.addPreference(license);
    }

    private Preference info(int titleRes, CharSequence summary) {
        Preference p = new Preference(requireContext());
        p.setTitle(titleRes);
        p.setSummary(summary);
        return p;
    }

    private Preference buildLinksPreference() {
        Preference links = new Preference(requireContext()) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                TextView tv = (TextView) holder.findViewById(R.id.about_links);
                if (tv == null) {
                    return;
                }
                tv.setText(buildLinks());
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                tv.setHighlightColor(Color.TRANSPARENT);
            }
        };
        links.setLayoutResource(R.layout.preference_about_links);
        links.setTitle(R.string.about_links_title);
        links.setSummary(R.string.about_links_summary);
        links.setSelectable(false);
        return links;
    }

    private CharSequence buildLinks() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int color = ContextCompat.getColor(requireContext(), R.color.about_link);
        for (int i = 0; i < LINK_URLS.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String label = getString(LINK_NAME_RES[i]) + " (" + getString(LINK_NAME_EN_RES[i]) + ")";
            if (LINK_PACKAGES[i].equals(CURRENT_APPLICATION_ID)) {
                label += getString(R.string.about_current_mark);
            }
            int start = sb.length();
            sb.append(label);
            int end = sb.length();

            final String url = LINK_URLS[i];
            ClickableSpan span = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    openUrl(url);
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(color);
                    ds.setUnderlineText(false);
                }
            };
            sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.action_open_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        if (mVersionFuture != null) {
            mVersionFuture.cancel(true);
            mVersionFuture = null;
        }
        super.onDestroyView();
    }

    private CharSequence versionSummary() {
        synchronized (VERSION_LOCK) {
            return getString(R.string.about_version, sVersionName, sVersionCode);
        }
    }

    /** 版本号只查一次；已缓存时直接复用，避免每次进入关于页重复 PackageManager IPC。 */
    private void loadVersionIfNeeded() {
        synchronized (VERSION_LOCK) {
            if (sVersionLoaded) {
                return;
            }
        }
        final Context appContext = requireContext().getApplicationContext();
        final Activity host = requireActivity();
        mVersionFuture = BG_EXECUTOR.submit(() -> {
            String name = "?";
            int code = 0;
            try {
                PackageInfo pi = appContext.getPackageManager()
                        .getPackageInfo(appContext.getPackageName(), 0);
                if (pi.versionName != null) {
                    name = pi.versionName;
                }
                code = (int) pi.getLongVersionCode();
            } catch (Throwable ignored) {
                // 查询失败保留占位值，不影响关于页其他内容。
            }
            synchronized (VERSION_LOCK) {
                sVersionName = name;
                sVersionCode = code;
                sVersionLoaded = true;
            }
            final String loadedName = name;
            final int loadedCode = code;
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                Preference p = findPreference(KEY_VERSION);
                if (p != null) {
                    p.setSummary(getString(R.string.about_version, loadedName, loadedCode));
                }
            });
        });
    }
}
