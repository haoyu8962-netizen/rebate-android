package com.couli.app.catalog

/** 独立验收清单；不从生产目录注册表推导，防实现与测试一起漏项。 */
data class Ds02Case(
    val component: String,
    val section: String,
    val kind: String,
    val variant: String,
    val state: String,
) {
    val key: String get() = "$variant/$state"
    val tag: String get() = "catalog-sample/$component/$key"

    override fun toString(): String = "$component/$key"
}

internal object Ds02Inventory {
    val cases: List<Ds02Case> =
        requireNotNull(javaClass.getResourceAsStream("/ds02-inventory.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .flatMap { line ->
                        val fields = line.split('|')
                        require(fields.size == 5) { line }
                        fields[3].split(',').flatMap { variant ->
                            fields[4].split(',').map { state ->
                                Ds02Case(fields[0], fields[1], fields[2], variant, state)
                            }
                        }
                    }.toList()
            }
}
