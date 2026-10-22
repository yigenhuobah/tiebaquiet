package io.github.yigenhuobah.tiebaquiet;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;

import io.github.yigenhuobah.tiebaquiet.settings.SettingsRepository;

/**
 * 单 Activity 外壳：工具栏 + Fragment 容器。
 *
 * 主页面是 {@link SettingsFragment}；诊断与关于是二级页。进入二级页时工具栏
 * 显示返回箭头，返回后由 FragmentManager 恢复主页面的视图状态（含滚动位置）。
 */
public class MainActivity extends AppCompatActivity {

    private MaterialToolbar mToolbar;

    private final FragmentManager.OnBackStackChangedListener mBackStackListener =
            this::syncToolbar;

    /** 框架绑定/断开会触发；静态集合只持有这个 Runnable，不持有 Activity。 */
    private final Runnable mRefreshListener = () -> runOnUiThread(this::refreshCurrent);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前调用，否则动态取色不会生效。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setTitle(R.string.app_name);
        mToolbar.setSubtitle(getString(R.string.app_subtitle, versionName()));
        mToolbar.setNavigationOnClickListener(v -> {
            if (!getSupportFragmentManager().popBackStackImmediate()) {
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        getSupportFragmentManager().addOnBackStackChangedListener(mBackStackListener);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
        syncToolbar();

        applyInsets();

        // 顺序不能反：先挂监听再 init，init 对已缓存的 binder 会立即回调，
        // 回调必须先挂上，否则首次绑定会被空回调吃掉、永远等不到重绘。
        SettingsRepository.addListener(mRefreshListener);
        SettingsRepository.init(getApplicationContext());
        SettingsRepository.refresh();
    }

    /** 二级页显示返回箭头；主页面隐藏，避免出现“返回即退出”的误导。 */
    private void syncToolbar() {
        boolean secondary = getSupportFragmentManager().getBackStackEntryCount() > 0;
        if (secondary) {
            mToolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            mToolbar.setNavigationContentDescription(R.string.action_back);
        } else {
            mToolbar.setNavigationIcon(null);
            mToolbar.setNavigationContentDescription((CharSequence) null);
        }
    }

    /** targetSdk 36 强制 edge-to-edge，内容会画到状态栏/导航栏下面，必须自己吃 insets。 */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    private void refreshCurrent() {
        // binder 回调可能晚于 Activity 销毁，避免在已销毁的实例上操作 FragmentManager。
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        }
    }

    @Override
    protected void onDestroy() {
        SettingsRepository.removeListener(mRefreshListener);
        getSupportFragmentManager().removeOnBackStackChangedListener(mBackStackListener);
        super.onDestroy();
    }

    private String versionName() {
        try {
            String v = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return v != null ? v : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
