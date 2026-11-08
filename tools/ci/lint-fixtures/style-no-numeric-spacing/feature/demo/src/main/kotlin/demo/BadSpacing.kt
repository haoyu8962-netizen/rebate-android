// Scanner fixture; intentionally not compiled.
package demo

fun badSpacing() {
    Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    Arrangement.spacedBy(6.dp)
    Spacer(Modifier.height(12.dp))
}
