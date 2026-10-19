package io.github.yigenhuobah.tiebaquiet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.yigenhuobah.tiebaquiet.BuildConfig
import io.github.yigenhuobah.tiebaquiet.feature.Feature
import io.github.yigenhuobah.tiebaquiet.feature.FeatureGroup
import io.github.yigenhuobah.tiebaquiet.feature.FeatureRegistry
import io.github.yigenhuobah.tiebaquiet.settings.SettingsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(applicationContext)
        setContent {
            TiebaQuietTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}

/** Material 3 + DayNight，API 31+ 跟随系统取色（Monet），低版本回退静态色板。 */
@Composable
private fun TiebaQuietTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(SettingsRepository.status()) }
    var revision by remember { mutableStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

    val targetVersion = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SettingsRepository.TARGET_PACKAGE, 0).versionName
        } catch (_: Throwable) {
            null
        }
    }
    val versionMismatch = targetVersion != null && targetVersion != SettingsRepository.TARGET_VERSION

    DisposableEffect(Unit) {
        val listener = {
            status = SettingsRepository.status()
            revision = revision + 1
        }
        SettingsRepository.addListener(listener)
        onDispose { SettingsRepository.removeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                SettingsRepository.refresh()
                status = SettingsRepository.status()
                revision = revision + 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { showAbout = true },
                    ) {
                        Text("贴吧静净", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                StatusCard(
                    status = status,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
                )
            }

            if (versionMismatch) {
                item {
                    WarningBanner(
                        text = "当前贴吧版本 $targetVersion，本模块仅适配 ${SettingsRepository.TARGET_VERSION}。未适配的功能会在启动时自动跳过，其余功能不受影响。",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }

            item {
                SignButton(
                    enabled = SettingsRepository.writable(),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
                )
            }

            FeatureRegistry.groups.forEach { (group, features) ->
                item { SectionHeader(group) }
                items(features, key = { it.id }) { feature ->
                    FeatureRow(
                        feature = feature,
                        revision = revision,
                        enabled = SettingsRepository.writable(),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            item {
                Footer()
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于贴吧静净") },
            text = {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}\n" +
                        "适配百度贴吧 ${SettingsRepository.TARGET_VERSION} · libxposed API 102\n\n" +
                        "本模块与百度贴吧官方无任何关联，仅供学习、研究和个人设备使用。" +
                        "使用可能违反目标应用的服务条款，风险由使用者自行承担。\n\n" +
                        "MIT License · github.com/yigenhuobah/tiebaquiet",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun StatusCard(status: SettingsRepository.Status, modifier: Modifier = Modifier) {
    val title: String
    val detail: String
    val accent: Color
    when {
        !status.connected -> {
            title = "正在连接框架…"
            detail = "绑定成功后自动刷新"
            accent = MaterialTheme.colorScheme.onSurfaceVariant
        }
        !status.scoped -> {
            title = "未激活"
            detail = "请在 LSPosed 中启用本模块并勾选百度贴吧"
            accent = MaterialTheme.colorScheme.error
        }
        status.injected -> {
            title = "已激活"
            detail = "框架 API ${status.apiVersion} · 已注入百度贴吧"
            accent = MaterialTheme.colorScheme.primary
        }
        else -> {
            title = "框架已连接"
            detail = "启动贴吧后生效；若已启动仍显示此状态，请检查模块是否已启用"
            accent = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            Spacer(Modifier.padding(top = 2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SignButton(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = {
            SettingsRepository.setBoolean("one_key_sign", true)
            // 贴吧在后台时不允许其进程直接拉起 Activity：先把贴吧带到前台再发命令。
            val launch = context.packageManager
                .getLaunchIntentForPackage(SettingsRepository.TARGET_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    context.sendBroadcast(
                        Intent(io.github.yigenhuobah.tiebaquiet.feature.phase2.SignInFeature.ACTION_SIGN)
                            .setPackage(SettingsRepository.TARGET_PACKAGE),
                    )
                }, 1200)
            }
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("一键签到（需已登录贴吧）")
    }
}

@Composable
private fun SectionHeader(group: FeatureGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(group.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureRow(feature: Feature, revision: Int, enabled: Boolean) {
    var checked by remember(feature.id, revision) {
        mutableStateOf(SettingsRepository.getBoolean(feature.id, feature.defaultEnabled))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(enabled = enabled) {
                checked = !checked
                SettingsRepository.setBoolean(feature.id, checked)
            }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feature.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = feature.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                checked = it
                SettingsRepository.setBoolean(feature.id, it)
            },
        )
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "已知限制：一键签到要求吧内等级 7 级以上（贴吧服务端限制）；顶部频道栏“有料”红点不在清理范围；“启动任务精简”因会导致贴吧启动闪退已移除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "修改开关后需强制停止并重新打开贴吧生效。若某项无效，请确认 LSPosed 作用域已勾选 com.baidu.tieba。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
