package com.couli.app.clientcore

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.privacy.PermissionActivityHost
import com.couli.core.privacy.PermissionPrompt
import com.couli.core.privacy.PermissionPrompts
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CompletableDeferred

/** 用途说明浮层的测试标记。 */
const val PERMISSION_RATIONALE_TAG = "permission_rationale"

/**
 * MainActivity 的系统授权框（ActivityResult）。只给 core:privacy 的 AndroidPermissionSystem 用，业务代码不直接申请（03 §4.9）。
 * 同一时刻只有一个申请（PermissionRequester 串行）。
 */
class ActivityPermissionHost(
    private val activity: ComponentActivity,
) : PermissionActivityHost {
    private var pending: CompletableDeferred<Boolean>? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }

    override fun shouldShowRationale(androidPermission: String): Boolean = activity.shouldShowRequestPermissionRationale(androidPermission)

    override suspend fun request(androidPermission: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = result
        launcher.launch(androidPermission)
        return result.await()
    }
}

/**
 * 权限界面（APP-03b，03 §4.9；BR-TEXT-14 表 D）：Android 在系统框弹出的同时于屏幕顶部显示用途说明浮层（没有按钮）；永久拒绝或节流时
 * 用 CouliDialog 引导【去设置】（perm.go_settings），点了跳系统里本 App 的设置页。文案全部取键。
 */
@Composable
fun PermissionPromptHost(prompts: PermissionPrompts) {
    val prompt by prompts.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when (val current = prompt) {
        is PermissionPrompt.Rationale -> {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = DefaultTexts.text(current.purposeKey).orEmpty(),
                    style = CouliTypography.body,
                    color = CouliColors.textPrimary,
                    modifier =
                        Modifier
                            .padding(CouliSpacing.space4)
                            .fillMaxWidth()
                            .couliCard()
                            .padding(CouliSpacing.space4)
                            .testTag(PERMISSION_RATIONALE_TAG),
                )
            }
        }

        is PermissionPrompt.SettingsGuide -> {
            CouliDialog(
                title = DefaultTexts.text(current.messageKey ?: current.purposeKey).orEmpty(),
                confirmText = DefaultTexts.text(current.buttonKey).orEmpty(),
                onConfirm = {
                    prompts.dismiss(current)
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                onDismissRequest = { prompts.dismiss(current) },
            )
        }

        null -> {
            Unit
        }
    }
}
