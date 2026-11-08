// Scanner fixture; intentionally not compiled.
package demo

val longSuffix = Color(0xFF123456L)
val channels = Color(red = 0.1f, green = 0.2f, blue = 0.3f)
val parsed = android.graphics.Color.parseColor("#FF123456")
val parsedExt = "#123456".toColorInt()
val textUnit = TextUnit(14f, TextUnitType.Sp)
val em = 1.2.em
val floatSp = 14f.sp
val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
val floatCorner = RoundedCornerShape(8f)
val cut = CutCornerShape(8.dp)

fun equivalentForms() {
    Modifier.padding(Dp(8f))
    LazyColumn(contentPadding = PaddingValues(8.dp))
}
