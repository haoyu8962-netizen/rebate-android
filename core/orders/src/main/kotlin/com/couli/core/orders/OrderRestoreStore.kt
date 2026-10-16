package com.couli.core.orders

import com.couli.core.clientcore.AtomicTextFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 订单页恢复记录里的个人数据部分（FU-AND-0710 R1-03 / R2-02，APP-08 共性第 36、40、45 条）：账号归属（账号哈希）、平台与月份筛选、
 * 搜索词与输入框文字（可能是完整订单号）、滚动锚点所在的订单。系统保存状态（SavedStateHandle）里只存一个随机引用与不含个人数据的值。
 */
data class OrderRestoreRecord(
    val owner: String?,
    val platform: String? = null,
    val month: String? = null,
    val q: String? = null,
    val input: String = "",
    val anchorOrderId: String? = null,
)

/**
 * 订单页恢复记录的落盘（App 放在 noBackupFilesDir 下）：每份记录一个文件（文件名 = 随机引用），原子写（临时文件改名替换，失败抛错）。
 *
 * - 写入只认「当前清理代次」（[epoch]）：每次 [clearAll] 先加 1，页面在清理之前取得的代次写不进来（会话清理后旧页面的迟到写入作废）；
 *   页面清空自己的内存状态后（AppOrders 的清理通知）再取新的代次。
 * - [clearAll]（撤回同意、进入基本模式、切换环境；AppModule 直接登记进凭据清理器与环境清理器，Application 启动的补清理与环境对账同样执行）：
 *   先把「待清理」落到记录目录之外（[flag]，commit 确认）并在目录内放标记，再删除全部记录、再列一次确认只剩标记，最后清标记；任何一步失败都抛
 *   [IOException]（清理器据此判失败、保留待清理并重试）。
 * - 待清理期间（本进程内存、目录外标记、目录内标记任一存在）：读一律为 null、写一律拒绝，每次读写前先重试清理；进程重建后同样生效
 *   （内存没了，两处落盘标记仍在）。目录外标记读不出按已设置。
 * - **归属世代**（F-AND-29，FU-AND-0714；鸿蒙 F-HM-25 / FU-HM-0711c R1-01 的做法）：接上持久化登录世代（[bindLoginEpoch]）之后，每份记录写入
 *   「账号归属 + 令牌存储里的持久化登录世代」的哈希（`gen`），读取时按此刻的登录世代重算核对，不一致（同账号重新登录、换号）或读不出（抛错、null、
 *   空串）一律不恢复。三处标记与删除同时失败（整体存储故障）、失败写入其实已落盘等残留，在重新登录之后也恢复不出来。登录世代在页面固定身份时取一次
 *   （[loginEpochNow]），写入时此刻的世代已不是它（同账号重新登录、清理通知还没到）就不写。
 */
@Suppress("TooManyFunctions") // 读、写、清理与编解码各一组
class OrderRestoreStore internal constructor(
    private val root: File,
    private val flag: OrderStaleCache.PendingClearFlag,
    /** 删除一个文件或目录并确认（测试替换：模拟删不掉）。 */
    private val deleter: (File) -> Boolean,
    /** 原子写入一个记录文件，失败抛错（测试替换：模拟写不进）。 */
    private val writer: (File, String) -> Unit,
) {
    constructor(root: File, flag: OrderStaleCache.PendingClearFlag = OrderStaleCache.SiblingFileFlag(root)) :
        this(root, flag, ::deleteConfirmed, { file, text -> AtomicTextFile(file).write(text) })

    private val marker = File(root, PENDING_MARKER)

    /** 本进程内的待清理状态（落盘的两处标记另行核验）。 */
    private var pendingClear = false

    /** 持久化登录世代的来源（F-AND-29）；没接（单测、旧用法）时不写不核对 `gen`。 */
    @Volatile
    private var loginEpoch: (() -> String?)? = null

    /** 接上令牌存储里的持久化登录世代（App 在每个取得实例的地方接；只能从未接变为接上）。 */
    fun bindLoginEpoch(source: () -> String?) {
        if (loginEpoch == null) loginEpoch = source
    }

    /** 此刻的持久化登录世代（观察用；页面固定身份用 [loginBindingNow]）；没接或读不出为 null。 */
    fun loginEpochNow(): String? = (currentEpoch() as? LoginBinding.Known)?.value

    /**
     * 页面固定身份时取一次的登录世代绑定（第 2 轮评审 R2-04）：明确区分**没接来源**（[LoginBinding.Unbound]，旧口径）与**绑定时读取失败**
     * （[LoginBinding.Unknown]）；写入时原样交回 [write]。
     */
    fun loginBindingNow(): LoginBinding = currentEpoch()

    /**
     * 页面身份快照里的持久化登录世代 → 绑定（F-X-19 补充二，FU-AND-0717：恢复决策与写入只用页面固定时那一份身份快照，不另读一次此刻的世代）：
     * 存储没接来源 → [LoginBinding.Unbound]（旧口径）；接了来源而快照给不出世代 → [LoginBinding.Unknown]；否则 [LoginBinding.Known]。
     */
    fun bindingFor(loginEpoch: String?): LoginBinding =
        when {
            this.loginEpoch == null -> LoginBinding.Unbound
            loginEpoch.isNullOrEmpty() -> LoginBinding.Unknown
            else -> LoginBinding.Known(loginEpoch)
        }

    /** 登录世代的三种读法：没接来源（旧口径）、读不出（未知）、读到。 */
    sealed interface LoginBinding {
        /** 取的那一刻存储没接登录世代来源（单测、旧用法）。 */
        data object Unbound : LoginBinding

        /** 接了来源但读不出（会话不可用、隔离、撤销、读取失败）：之后的写入一律拒绝，即使世代后来读得出（不补绑）。 */
        data object Unknown : LoginBinding

        data class Known(
            val value: String,
        ) : LoginBinding
    }

    private fun currentEpoch(): LoginBinding {
        val source = loginEpoch ?: return LoginBinding.Unbound
        return runCatching(source).getOrNull()?.takeIf { it.isNotEmpty() }?.let(LoginBinding::Known) ?: LoginBinding.Unknown
    }

    /** 清理代次：每次 [clearAll] 加 1；页面取得的代次与它不同就写不进来。 */
    @Volatile
    var epoch: Long = 0L
        private set

    /** 新的记录引用（随机，不含个人数据；写进系统保存状态）。 */
    fun newRef(): String = UUID.randomUUID().toString()

    /**
     * 读 [ref] 的记录；没有、引用不合法、待清理没清掉、读不出或损坏都为 null（损坏的删掉）。接上登录世代时：世代读不出不恢复；记录的 `gen` 与
     * 「记录的账号归属 + 此刻的登录世代」不符（旧登录世代、别的账号、升级前没有 `gen` 的记录）不恢复并删掉。
     */
    fun read(ref: String?): OrderRestoreRecord? = read(ref, currentEpoch())

    /**
     * 按页面身份快照里的绑定 [binding]（[bindingFor]）读 [ref] 的记录（F-X-19 补充二：核对 `gen` 用这一份，不另读此刻的世代）：绑定未知不恢复；
     * 记录的 `gen` 与「记录的账号归属 + 绑定的世代」不符不恢复并删掉。
     */
    @Synchronized
    @Suppress("ReturnCount") // 引用、清理、世代、读取逐项
    fun read(
        ref: String?,
        binding: LoginBinding,
    ): OrderRestoreRecord? {
        if (ref == null || !REF_PATTERN.matches(ref) || !cleaned()) return null
        val current = if (loginEpoch == null) LoginBinding.Unbound else binding
        if (current == LoginBinding.Unknown) return null
        val file = File(root, ref + SUFFIX)
        val text = runCatching { AtomicTextFile(file).read() }.getOrNull() ?: return null
        val record = decode(text)
        if (record == null || (current is LoginBinding.Known && decodeGen(text) != bindingHash(record.owner, current.value))) {
            runCatching { deleter(file) }
            return null
        }
        return record
    }

    /**
     * 写 [ref] 的记录（世代以字符串给出的简写）：[boundLoginEpoch] 为 null 按「调用方没有绑定」（[LoginBinding.Unbound]）——存储接了登录世代来源时
     * 一律拒写，**不用此刻的世代补绑**（第 2 轮评审 R2-04）。页面用 [loginBindingNow] 取绑定、按 LoginBinding 版本写。
     */
    fun write(
        ref: String,
        boundEpoch: Long,
        record: OrderRestoreRecord,
        boundLoginEpoch: String? = null,
    ): Boolean = write(ref, boundEpoch, record, boundLoginEpoch?.let(LoginBinding::Known) ?: LoginBinding.Unbound)

    /**
     * 写 [ref] 的记录：[boundEpoch] 必须仍是当前清理代次、没有待清理；登录世代（第 2 轮评审 R2-04）：
     * - 存储没接来源：只有同样没接时取的绑定（[LoginBinding.Unbound]）能写（旧口径，不带 `gen`）；
     * - 接了来源：绑定必须是**已知**的（[LoginBinding.Known]）且等于此刻读得出的世代——绑定时读不出（[LoginBinding.Unknown]）、取绑定时还没接来源、
     *   此刻读不出、此刻已换代（同账号重新登录、清理通知还没到）都拒写。不用此刻的世代给未知的旧页面补绑。
     * 写不进返回 false（调用方当作没有恢复记录）。
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun write(
        ref: String,
        boundEpoch: Long,
        record: OrderRestoreRecord,
        binding: LoginBinding,
    ): Boolean {
        if (!REF_PATTERN.matches(ref) || !cleaned() || boundEpoch != epoch) return false
        val gen =
            when (val current = currentEpoch()) {
                LoginBinding.Unbound -> {
                    if (binding != LoginBinding.Unbound) return false
                    null
                }

                LoginBinding.Unknown -> {
                    return false
                }

                is LoginBinding.Known -> {
                    if (binding !is LoginBinding.Known || binding.value != current.value) return false
                    bindingHash(record.owner, current.value)
                }
            }
        val ok = runCatching { writer(File(root, ref + SUFFIX), encode(record, gen)) }.isSuccess
        if (!ok) OrderLog.warn("orders: restore record not written")
        return ok
    }

    /**
     * 会话对账（第 1 轮修复 R1-05，FU-AND-0717；与订单缓存同一份身份快照、同一入口，不依赖页面创建或读取）：已确认的身份 [account]（null = 已确认没有会话）
     * 与持久化登录世代 [loginEpoch] 下，凡是归属（账号哈希）或 `gen`（账号归属 + 世代）不属于这个身份的记录一律删除。接了世代来源而 [loginEpoch] 给不出时
     * 判不清，不动（返回 true）；没接来源（旧口径）只按账号归属。先完成已登记的待清理。返回是否完成（删不掉为 false，调用方登记、之后重试）。
     */
    @Synchronized
    @Suppress("ReturnCount") // 待清理、判不清、逐条删除
    fun reconcileOwner(
        account: String?,
        loginEpoch: String?,
    ): Boolean {
        if (!cleaned()) return false
        val bound = this.loginEpoch != null
        if (account != null && bound && loginEpoch.isNullOrEmpty()) return true
        val owner = account?.let(::ownerHash)
        val files = runCatching { root.listFiles() }.getOrNull() ?: return !root.exists()
        val stale =
            files.filter { it.isFile && it.name.endsWith(SUFFIX) && it.name != PENDING_MARKER }.filter { file ->
                val text = runCatching { AtomicTextFile(file).read() }.getOrNull() ?: return@filter true
                val record = decode(text) ?: return@filter true
                when {
                    owner == null -> true
                    record.owner != owner -> true
                    bound -> decodeGen(text) != bindingHash(record.owner, loginEpoch.orEmpty())
                    else -> false
                }
            }
        return stale.all { runCatching { deleter(it) }.getOrDefault(false) }
    }

    /** 只完成已登记的待清理（身份暂时未知时也照常，F-X-19 补充二）；返回此刻是否没有待清理。 */
    @Synchronized
    fun completePendingClear(): Boolean = cleaned()

    /** 页面创建时只留本页这一份（进程被杀、没被恢复的旧记录删掉）；删不掉不影响页面（清理时再删）。 */
    @Synchronized
    fun retainOnly(ref: String?) {
        if (!cleaned()) return
        val keep = ref?.plus(SUFFIX)
        runCatching { root.listFiles() }
            .getOrNull()
            ?.filter { it.name != keep && it.name != PENDING_MARKER }
            ?.forEach { runCatching { deleter(it) } }
    }

    /**
     * 全部删除（撤回同意、进入基本模式、切换环境）：代次先加 1（之前取得代次的写入一律作废），任何一个文件删不掉都保留待清理并抛错。
     */
    @Synchronized
    fun clearAll() {
        epoch += 1
        clearFiles()
    }

    private fun clearFiles() {
        pendingClear = true
        // 先把待清理状态落到记录目录之外并确认写入；写不进时仍尽力删除，并在目录内留标记（进程重建后也读得到）
        if (!flag.set()) OrderLog.warn("orders: restore pending flag not persisted")
        if (!root.exists()) return finishClear()
        runCatching { if (root.isDirectory) marker.writeText("1") }
        val children = (if (root.isDirectory) root.listFiles() else null) ?: incomplete("restore directory not listable")
        val leftovers = children.filter { it.name != PENDING_MARKER }.filterNot { runCatching { deleter(it) }.getOrDefault(false) }
        if (leftovers.isNotEmpty()) incomplete("restore records not fully deleted")
        val remaining = root.listFiles() ?: incomplete("restore directory not listable")
        if (remaining.any { it.name != PENDING_MARKER }) incomplete("restore records not fully deleted")
        if (marker.exists() && !marker.delete()) incomplete("restore marker not deleted")
        finishClear()
    }

    /** 确认全部删除之后：先清目录外的待清理状态（确认写入），再清内存状态；清不掉按未完成。 */
    private fun finishClear() {
        if (!flag.clear()) incomplete("restore pending flag not cleared")
        pendingClear = false
    }

    private fun incomplete(reason: String): Nothing = throw IOException(reason)

    /** 没有待清理，或重试清理成功（重试不改代次）。目录外标记读不出按已设置（未知）。 */
    private fun cleaned(): Boolean {
        val pending = pendingClear || marker.exists() || runCatching { flag.isSet() }.getOrDefault(true)
        if (!pending) return true
        return runCatching { clearFiles() }.isSuccess
    }

    private fun encode(
        record: OrderRestoreRecord,
        gen: String?,
    ): String =
        JsonObject(
            mapOf(
                "owner" to str(record.owner),
                "platform" to str(record.platform),
                "month" to str(record.month),
                "q" to str(record.q),
                "input" to JsonPrimitive(record.input),
                "anchor_order_id" to str(record.anchorOrderId),
            ) + (gen?.let { mapOf("gen" to JsonPrimitive(it)) } ?: emptyMap()),
        ).toString()

    private fun decodeGen(text: String): String? =
        ((runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull())?.get("gen") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    /** 归属世代哈希：账号归属 + 持久化登录世代（SHA-256 十六进制；不落明文）。 */
    private fun bindingHash(
        owner: String?,
        loginEpoch: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${owner.orEmpty()}\u0000login_epoch:$loginEpoch".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun decode(text: String): OrderRestoreRecord? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null

        fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return OrderRestoreRecord(
            owner = text("owner"),
            platform = text("platform"),
            month = text("month"),
            q = text("q"),
            input = text("input").orEmpty(),
            anchorOrderId = text("anchor_order_id"),
        )
    }

    private fun str(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

    private companion object {
        /** 账号 → 记录里的归属（与页面写入的 owner 同一算法：OrderStaleCache.Store.hash）。 */
        fun ownerHash(account: String): String = OrderStaleCache.Store.hash(account)

        const val PENDING_MARKER = "pending_clear"
        const val SUFFIX = ".json"
        val REF_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

private fun deleteConfirmed(file: File): Boolean = file.deleteRecursively() && !file.exists()
