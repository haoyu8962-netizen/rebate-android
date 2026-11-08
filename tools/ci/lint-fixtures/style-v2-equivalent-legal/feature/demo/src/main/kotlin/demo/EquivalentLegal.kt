// Scanner fixture; intentionally not compiled.
package demo

const val FONT = 14
const val OPAQUE = 0xFFL
val PAD = 8.dp
val GAP = Dp(8f)
val transparent = Color.Transparent
val tokenText = TextStyle(fontSize = CouliTypography.body.fontSize)
val constantUnit = TextUnit(FONT.toFloat(), TextUnitType.Sp)
val tokenCut = CutCornerShape(CouliRadius.button)
val anchor = "#section"

fun equivalentLegal(serverValue: String) {
    android.graphics.Color.parseColor(serverValue)
    Modifier.width(Dp(48f)).padding(PAD)
    Arrangement.spacedBy(GAP)
    BorderStroke(Dp(1f), CouliColors.borderSubtle)
    Modifier.clip(CircleShape)
}
