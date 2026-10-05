package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.generated.DesignTokens

/** 间距令牌 space.*（dp）。 */
@Immutable
object CouliSpacing {
    val space0: Dp = token("0")
    val space1: Dp = token("1")
    val space2: Dp = token("2")
    val space3: Dp = token("3")
    val space4: Dp = token("4")
    val space5: Dp = token("5")
    val space6: Dp = token("6")
    val space8: Dp = token("8")
    val space10: Dp = token("10")
    val space12: Dp = token("12")
    val space16: Dp = token("16")

    private fun token(key: String): Dp = DesignTokens.spacing.getValue(key).dp
}

/** 圆角令牌 radius.*（dp）。 */
@Immutable
object CouliRadius {
    val small: Dp = token("small")
    val button: Dp = token("button")
    val card: Dp = token("card")
    val panel: Dp = token("panel")
    val pill: Dp = token("pill")

    private fun token(key: String): Dp = DesignTokens.radius.getValue(key).dp
}

/** 交互尺寸。 */
@Immutable
object CouliInteraction {
    /** 最小命中区：interaction.target.android（dp）；命中区下限不随字号缩小（03 §10.4）。 */
    val minTouchTarget: Dp = DesignTokens.interactionTarget.getValue("android").dp
}
