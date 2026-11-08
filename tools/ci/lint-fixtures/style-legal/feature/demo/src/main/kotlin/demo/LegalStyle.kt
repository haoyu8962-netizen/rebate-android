// Scanner fixture; intentionally not compiled.
package demo

const val GAP = 8
const val RADIUS = 8
const val FONT = 14
val transparent = Color.Transparent
val tokenColor = CouliColors.brandPrimary
val corner = RoundedCornerShape(CouliRadius.button)
val textSize = FONT.sp
val waived = Color(0xFF123456) // couli-style-disable-line couli-style/no-hex-color -- external brand swatch

fun legalStyle() {
    Modifier.padding(0.dp).padding(CouliSpacing.space2).padding(GAP.dp)
    Spacer(Modifier.height(0.dp))
    Box(Modifier.width(48.dp).height(24.dp).size(16.dp))
    Modifier.border(1.dp, tokenColor)
    RoundedCornerShape(RADIUS)
}
