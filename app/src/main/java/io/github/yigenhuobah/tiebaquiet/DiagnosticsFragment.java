package io.github.yigenhuobah.tiebaquiet;

import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

/**
 * 诊断页占位实现。
 *
 * <p>本轮只显示空状态与说明；Phase 6 接入目标进程 HookState → hooks/Report →
 * StatusProvider 后，再按 Feature 清单展示逐功能 registered / hit / skip。
 * 主页面保持零诊断，诊断细节只放在本页。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        Preference header = new Preference(requireContext());
        header.setTitle(R.string.diag_title);
        header.setSummary(R.string.diag_placeholder);
        header.setSelectable(false);
        screen.addPreference(header);

        Preference empty = new Preference(requireContext());
        empty.setTitle(R.string.diag_empty_title);
        empty.setSummary(getString(R.string.diag_empty, getString(R.string.app_target_name)));
        empty.setSelectable(false);
        screen.addPreference(empty);
    }
}
