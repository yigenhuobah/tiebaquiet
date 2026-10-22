package io.github.yigenhuobah.tiebaquiet;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Material 3 开关行。
 *
 * 不使用 androidx.preference.SwitchPreferenceCompat：默认外观是旧版 SwitchCompat，
 * 与 Material 3 主题不搭。这里自己绑 MaterialSwitch，并关闭本地持久化 ——
 * 开关值统一由 {@link io.github.yigenhuobah.tiebaquiet.settings.SettingsRepository}
 * 写进框架的 remote preferences，否则贴吧进程读不到。
 */
public class MaterialSwitchPreference extends Preference {

    private boolean mChecked;

    public MaterialSwitchPreference(Context context) {
        this(context, null);
    }

    public MaterialSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_material_switch);
        // 值由 SettingsRepository 统一写入 remote prefs，Preference 自己不再存一份。
        setPersistent(false);
    }

    /** 由 SettingsFragment.refresh 调用：只更新显示值，不触发 change 回调。 */
    public void setCheckedValue(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }

    public boolean isCheckedValue() {
        return mChecked;
    }

    /**
     * android:dependency 依赖这个：父开关关闭时把子项置灰。
     * 基类默认看 isEnabled()，对开关语义不对。
     */
    @Override
    public boolean shouldDisableDependents() {
        return !mChecked || super.shouldDisableDependents();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        MaterialSwitch sw = (MaterialSwitch) holder.findViewById(R.id.preference_switch);
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(mChecked);
        sw.setOnCheckedChangeListener((button, checked) -> {
            // 只有用户真正点按才处理；程序 setChecked 引起的回调忽略，避免回环。
            if (!button.isPressed()) {
                return;
            }
            if (callChangeListener(checked)) {
                mChecked = checked;
                notifyDependencyChange(shouldDisableDependents());
            } else {
                // 写入失败时回滚，避免“看着改了实际没写进去”。
                button.setChecked(mChecked);
            }
        });
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        boolean next = !mChecked;
        if (callChangeListener(next)) {
            mChecked = next;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }
}
