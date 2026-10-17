package com.couli.core.home

import android.annotation.SuppressLint
import android.content.Context
import com.couli.core.clientcore.AtomicTextFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 首页 LKG（G16 ①②、G30，TECH-10；APP-03b 第 1 条，APP-08 第 20、28 条，APP-09 第 5 条）：
 * - 存 noBackupFilesDir/couli_home_lkg/<作用域摘要>/page.json（作用域 = 环境 + 账号作用域，目录名是 SHA-256 前 16 位，不落明文账号）；原子写（临时文件 + 改名，
 *   AtomicTextFile），临时文件也在清理范围；只存服务端下发的页面 JSON，不存令牌、手机号；
 * - 「待清理」标记在缓存目录之外（[PendingFlag]，生产是 SharedPreferences + commit 核对），启动与读 LKG 前先核验并补清；区分「目录不存在」与「枚举失败」，
 *   删除确认落盘才清标记；标记也写不进时本进程内阻断读取，并把失败上抛给清理协调器（共性第 8 条）；
 * - 清理事件：撤回同意、切换环境 → [clearAll]；退出登录、换账号、会话失效 → [clearScope]（旧作用域）；清除缓存、回后台 → 不清。
 * - **写入世代票据**（共性第 23 条，R1-01）：写入方在身份固定时取 [ticket]（「全部」与该作用域的清理世代），[write] 在与清理共用的这把锁里核对；
 *   [clearAll] / [clearScope] 进锁后先推进世代再删除。已经在等锁的旧写入，等清理完成后拿到锁也会因世代不符被拒，不会让文件复活（只取消协程挡不住它）。
 * - **接受顺序序号**（共性第 32 条，R2-01）：写入方在**接受结果的那一刻**（主线程）取 [reserve]（进程内单调递增，跨控制器也单调），[write] / [discard]
 *   在同一把锁里拒绝不比「该作用域已落盘的序号」更新的写入。两次有效写入即使逆序拿到锁，旧结果也盖不掉新结果。
 * - **持久化登录世代分区**（F-AND-25，FU-AND-0714 第 1 轮评审 R1-07）：接上 [bindLoginEpoch] 之后，会员作用域（[HomeLkgScope.isMember]）的页面存在
 *   作用域目录下再按「登录世代摘要」分的子目录里（<作用域摘要>/e-<世代摘要>/page.json）；作用域目录名与 [HomeLkgScope] 向量不变。世代读不出（null、空串、抛错）
 *   按未知：不读、不写。写入票据记下取票时的世代，写入时世代已变的不写。待清理标记与删除都失败、残留的旧页面，同账号重新登录（新世代）或进程重建之后都读不回；
 *   [clearScope] / [clearAll] 删整个作用域目录（含全部世代子目录）。游客、基本模式不分世代（原口径）。没接（单测、预览）时按原口径。
 * 只做同步文件操作（调用方放在 IO 线程）；线程安全。
 */
@Suppress("TooManyFunctions") // 读、写（两种）、删坏件、两种清理、核验与票据、发号、登录世代
class HomeLkgStore(
    private val root: File,
    private val flag: PendingFlag,
    /** 列目录（只有测试替换：模拟目录在、却枚举失败）。 */
    private val lister: (File) -> Array<File>? = { it.listFiles() },
    /** 删除一个作用域目录（只有测试替换：模拟删除失败）。 */
    private val deleter: (File) -> Boolean = { it.deleteRecursively() && !it.exists() },
) {
    /** 目录外的待清理标记（条目是作用域摘要或 [ALL]）；读不出为 null（按全部待清理处理）。 */
    interface PendingFlag {
        fun entries(): Set<String>?

        fun add(entry: String): Boolean

        fun remove(entry: String): Boolean
    }

    /** 写入票据：取票时「全部」与该作用域（摘要）的清理世代；只比较，不可改。 */
    data class WriteTicket internal constructor(
        val key: String,
        val allGeneration: Long,
        val scopeGeneration: Long,
        /** 会员作用域取票时的登录世代分区（[bindLoginEpoch] 之后；世代未知为 [UNKNOWN_EPOCH]，写入一律拒绝）；不分世代为 null。 */
        val epochDir: String? = null,
    )

    /**
     * 持久化登录世代的来源（F-AND-25，R1-07，[bindLoginEpoch]）：接上之后会员作用域按世代分区；没接为 null（原口径）。
     */
    @Volatile
    private var loginEpoch: (() -> String?)? = null

    /** 接上令牌存储里的持久化登录世代（App 取首页实例时接；只能从未接变为接上）。 */
    fun bindLoginEpoch(source: () -> String?) {
        if (loginEpoch == null) loginEpoch = source
    }

    /**
     * [scope] 此刻的世代分区：不分世代（没接、非会员作用域）为 null；会员作用域世代读不出为 [UNKNOWN_EPOCH]；否则 `e-<世代摘要>`。
     */
    private fun epochDirOf(scope: String): String? = if (HomeLkgScope.isMember(scope)) currentEpochDir() else null

    /** 页面文件：作用域目录（+ 世代子目录）下的 page.json。 */
    private fun pageFile(
        key: String,
        epochDir: String?,
    ): File = File(if (epochDir == null) File(root, key) else File(File(root, key), epochDir), PAGE_FILE)

    /** 本进程内的阻断（标记写不进时兜底；进程退出后由目录外标记核验，三者都失败的残留见 rule-gaps）。 */
    private val blocked = mutableSetOf<String>()

    /** 清理世代：[clearAll] 推进 [allGeneration]，[clearScope] 推进该作用域的世代（都在锁内）；取票只读，不进锁（主线程取票不等 IO）。 */
    @Volatile private var allGeneration = 0L
    private val scopeGenerations = ConcurrentHashMap<String, Long>()

    /** 接受顺序：[reserve] 发号（任意线程）；[persisted] 是每个作用域已落盘（或已按序删除）的最大序号，只在锁内读写。 */
    private val orders = AtomicLong(0L)
    private val persisted = HashMap<String, Long>()

    /** 取一个接受顺序序号：在接受结果的那一刻取（主线程，按接受顺序），之后随写入一起交给 [write] / [discard]。 */
    fun reserve(): Long = orders.incrementAndGet()

    /** 取一张写入票据（身份固定时取一次：控制器构造时）。 */
    fun ticket(scope: String): WriteTicket {
        val key = digest(scope)
        return WriteTicket(key, allGeneration, scopeGenerations[key] ?: 0L, epochDirOf(scope))
    }

    /** 按首页固定身份的快照取票，不另读世代；未知票据拒绝写入，首次确认后由新控制器重新取票。 */
    fun ticket(
        scope: String,
        loginEpoch: String?,
    ): WriteTicket {
        val key = digest(scope)
        val epochDir =
            when {
                this.loginEpoch == null || !HomeLkgScope.isMember(scope) -> null
                loginEpoch.isNullOrEmpty() -> UNKNOWN_EPOCH
                else -> EPOCH_PREFIX + digest(loginEpoch)
            }
        return WriteTicket(key, allGeneration, scopeGenerations[key] ?: 0L, epochDir)
    }

    /** 读一个作用域的 LKG 原文；待清理未完成、登录世代未知（会员作用域）、没有文件、读不出 → null。 */
    @Synchronized
    fun read(scope: String): String? {
        val key = digest(scope)
        if (!settle() || key in blocked || ALL in blocked) return null
        val epochDir = epochDirOf(scope)
        return if (epochDir == UNKNOWN_EPOCH) null else runCatching { AtomicTextFile(pageFile(key, epochDir)).read() }.getOrNull()
    }

    /**
     * 按票据与接受顺序 [order]（[reserve] 取的）写一个作用域的 LKG（原子写）：票据之后发生过清理（世代不符）、待清理未完成、
     * [order] 不比该作用域已落盘的序号新（R2-01：更早接受的结果晚拿到锁）时都不写。返回是否写入。
     * 世代与序号都在与 [clearAll] / [clearScope] 共用的锁里核对：等锁的旧写入在清理完成后拿到锁，也会在这里被拒。
     */
    @Synchronized
    fun write(
        ticket: WriteTicket,
        order: Long,
        text: String,
    ): Boolean {
        val key = ticket.key
        if (!open(ticket) || order <= (persisted[key] ?: 0L)) return false
        val done = runCatching { AtomicTextFile(pageFile(key, ticket.epochDir)).write(text) }.isSuccess
        // 只有真正落盘才推进：没写成的较新结果不挡住之后到达的较旧结果（它仍比盘上的新）
        if (done) persisted[key] = order
        return done
    }

    /** 立即接受并写入（测试与没有并发的调用方；首页控制器在接受结果时另取 [reserve]）。 */
    fun write(
        ticket: WriteTicket,
        text: String,
    ): Boolean = write(ticket, reserve(), text)

    /**
     * 读出的 LKG 无效（解码失败）：按票据与接受顺序删掉，按没有 LKG 处理（G16）。只删读到的那一份（[expected]）：
     * 读与删之间落盘了别的结果（内容已变），或 [order] 不比已落盘的新，都不删。返回是否删除。
     */
    @Suppress("ReturnCount") // 票据与序号、内容核对依次提前返回
    @Synchronized
    fun discard(
        ticket: WriteTicket,
        order: Long,
        expected: String,
    ): Boolean {
        val key = ticket.key
        if (!open(ticket) || order <= (persisted[key] ?: 0L)) return false
        val file = AtomicTextFile(pageFile(key, ticket.epochDir))
        if (runCatching { file.read() }.getOrNull() != expected) return false
        val done = runCatching { file.write(null) }.isSuccess
        if (done) persisted[key] = order
        return done
    }

    /**
     * 票据仍有效（清理世代未变）、登录世代已知且仍是取票时那一份（会员作用域）、没有待清理：锁内调用。
     * 登录世代的复核只看「此刻的世代分区 == 票据的」：取票后重新登录（新世代）的旧写入不落进新世代。
     */
    private fun open(ticket: WriteTicket): Boolean {
        val key = ticket.key
        val current = ticket.allGeneration == allGeneration && ticket.scopeGeneration == (scopeGenerations[key] ?: 0L)
        val epochOk = ticket.epochDir != UNKNOWN_EPOCH && (ticket.epochDir == null || ticket.epochDir == currentEpochDir())
        return current && epochOk && settle() && key !in blocked && ALL !in blocked
    }

    /** 此刻会员作用域的世代分区（不看具体作用域；没接为 null）。 */
    private fun currentEpochDir(): String? {
        val source = loginEpoch ?: return null
        val epoch = runCatching(source).getOrNull()?.takeIf { it.isNotEmpty() }
        return if (epoch == null) UNKNOWN_EPOCH else EPOCH_PREFIX + digest(epoch)
    }

    /**
     * 会员作用域的登录世代对账（第 1 轮修复 R1-04，FU-AND-0717）：已确认的身份（[scope] 的账号 + 持久化登录世代 [loginEpoch]）下，删掉这个作用域里
     * 不属于当前世代的全部世代子目录（同账号重新登录后留下的旧世代 LKG）；当前世代的不动（未知 → 已知、同一世代都保留）。非会员作用域、没打开世代分区、
     * [loginEpoch] 给不出时不动。返回是否完成（删不掉 / 列不出为 false：调用方登记、下一次同步重试）。
     */
    @Synchronized
    @Suppress("ReturnCount") // 不适用、目录不在、列不出三个出口
    fun reconcileEpoch(
        scope: String,
        loginEpoch: String?,
    ): Boolean {
        if (!HomeLkgScope.isMember(scope) || this.loginEpoch == null || loginEpoch.isNullOrEmpty()) return true
        val dir = File(root, digest(scope))
        if (!dir.exists()) return true
        val keep = EPOCH_PREFIX + digest(loginEpoch)
        val children = lister(dir) ?: return false
        return children.filter { it.name.startsWith(EPOCH_PREFIX) && it.name != keep }.all { deleter(it) }
    }

    /** 清一个作用域（登出、换账号、会话失效时的旧作用域）：删不掉保留待清理并抛错。 */
    @Synchronized
    fun clearScope(scope: String) {
        val key = digest(scope)
        // 先推进世代（锁内）：之前取的票据从此全部作废
        scopeGenerations[key] = (scopeGenerations[key] ?: 0L) + 1
        clear(key)
    }

    /** 全部清除（撤回同意、切换环境）：先推进世代，再删；删不掉保留待清理并抛错；待清理期间不读回、不写入。 */
    @Synchronized
    fun clearAll() {
        allGeneration += 1
        clear(ALL)
    }

    /** 启动与读之前核验：目录外标记或本进程阻断里有待清理的，现在补清。全部清完返回 true。 */
    @Synchronized
    fun settle(): Boolean {
        val pending = (flag.entries() ?: setOf(ALL)) + blocked
        if (pending.isEmpty()) return true
        return pending.all { runCatching { clear(it) }.isSuccess }
    }

    private fun clear(entry: String) {
        blocked += entry
        val marked = flag.add(entry)
        val done = runCatching { delete(entry) }.isSuccess
        if (!done) {
            throw IOException(if (marked) "home lkg not fully deleted" else "home lkg not deleted and pending flag not written")
        }
        if (entry == ALL) {
            val leftovers = (flag.entries() ?: emptySet()) - ALL
            leftovers.forEach { flag.remove(it) }
            blocked.clear()
        }
        // 删除已确认：清标记；标记清不掉（下次核验时目录已不在，会再确认一次）也不再阻断本进程的读取
        flag.remove(entry)
        blocked -= entry
    }

    @Suppress("ThrowsCount") // 根不是目录、列不出、删不净各自报错
    private fun delete(entry: String) {
        if (!root.exists()) return
        if (!root.isDirectory) throw IOException("home lkg root is not a directory")
        if (entry == ALL) {
            val children = lister(root) ?: throw IOException("home lkg directory not listable")
            val leftovers = children.filterNot { deleter(it) }
            if (leftovers.isNotEmpty()) throw IOException("home lkg not fully deleted")
            val remaining = lister(root) ?: throw IOException("home lkg directory not listable")
            if (remaining.isNotEmpty()) throw IOException("home lkg not fully deleted")
        } else {
            val dir = File(root, entry)
            if (dir.exists() && !deleter(dir)) throw IOException("home lkg scope not deleted")
        }
    }

    companion object {
        const val ROOT_DIR = "couli_home_lkg"
        const val ALL = "*"
        private const val PAGE_FILE = "page.json"
        private const val HASH_HEX = 16

        /** 会员作用域的登录世代子目录前缀（e-<世代摘要>）。 */
        private const val EPOCH_PREFIX = "e-"

        /** 登录世代未知（读不出）：不是任何真实目录名，读写一律拒绝。 */
        private const val UNKNOWN_EPOCH = "?"

        /** 作用域 → 目录名（SHA-256 前 16 位十六进制）。 */
        fun digest(scope: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(scope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(HASH_HEX)
    }
}

/**
 * 待清理标记的生产实现：缓存目录之外的 SharedPreferences（[name] 不以 couli. 开头，环境切换的命名空间清理不会先抹掉它），commit 确认写入。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入
class SharedPreferencesPendingFlag(
    context: Context,
    name: String,
) : HomeLkgStore.PendingFlag {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun entries(): Set<String>? = runCatching { prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() }.getOrNull()

    @Suppress("ReturnCount")
    override fun add(entry: String): Boolean {
        val current = entries() ?: return false
        if (entry in current) return true
        return prefs.edit().putStringSet(KEY, HashSet(current + entry)).commit()
    }

    @Suppress("ReturnCount")
    override fun remove(entry: String): Boolean {
        val current = entries() ?: return false
        if (entry !in current) return true
        val next = current - entry
        val editor = prefs.edit()
        if (next.isEmpty()) editor.remove(KEY) else editor.putStringSet(KEY, HashSet(next))
        return editor.commit()
    }

    private companion object {
        const val KEY = "pending_clear"
    }
}

/**
 * 公告关闭记录与弹窗计数（G10、G11、G14 ⑤）：设备级（不按账号），存 SharedPreferences couli_home_prefs；登出、换账号、会话失效、清除缓存都不清，
 * 只在撤回同意与切换环境时整体清（登记进两个清理器，启动前可执行）。
 * - 关闭记录每条一个键（`n:` + 关闭键），只存组合键与时刻，不存公告正文与图片地址；**不按条数、不按时间淘汰**（BR-TEXT-13，共性第 9 条）——客户端拿不到
 *   「公告已失效」的确认。每条记录独立成键，不会把一个值越写越大（共性第 19 条；SharedPreferences 单值没有 8192 那样的上限，文件随真实关闭次数线性增长）；
 * - 弹窗计数每个 section id 一个键（`p:` + id）：「上海自然日|次数」，跨日自动从 0 计。
 * 写入一律 commit 并返回是否落盘；都带清理世代票据与接受顺序序号（[reserve]，R2-01），在与清理共用的锁里核对。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入
@Suppress("TooManyFunctions") // 关闭记录与弹窗计数各两个写入口（带序号 / 立即接受）、票据、发号、清理
class HomePrefsStore(
    context: Context,
    private val name: String = NAME,
    /** 清理进锁、推进世代之后、真正提交之前（只有测试替换：让旧写入在锁外排队）。 */
    private val beforeClearCommit: () -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** 同名存储在进程内共用一份锁与世代（清理器与首页各自 new 实例，也互斥、同世代）。 */
    private val shared: Shared = SHARED.getOrPut(name) { Shared() }

    private class Shared {
        val lock = Any()

        @Volatile var generation = 0L

        /** 接受顺序（R2-01，共性第 32 条）：发号单调递增；[persisted] 是每个键已落盘的最大序号（锁内读写）。 */
        val orders = AtomicLong(0L)
        val persisted = HashMap<String, Long>()
    }

    /** 取一个接受顺序序号（接受关闭 / 显示的那一刻取，随写入一起交给 [addClosed] / [markShown]）。 */
    fun reserve(): Long = shared.orders.incrementAndGet()

    /** 锁内：[order] 比 [prefKey] 已落盘的新。 */
    private fun newer(
        prefKey: String,
        order: Long,
    ): Boolean = order > (shared.persisted[prefKey] ?: 0L)

    /** 锁内：落盘成功才推进该键的序号。 */
    private fun committed(
        prefKey: String,
        order: Long,
        done: Boolean,
    ): Boolean {
        if (done) shared.persisted[prefKey] = order
        return done
    }

    /** 写入票据（共性第 23 条）：取票时的清理世代；[clearAll] 在锁内推进，写入在同一把锁里核对。 */
    data class WriteTicket internal constructor(
        val name: String,
        val generation: Long,
    )

    /** 取一张写入票据（身份固定时取一次：控制器构造时）。 */
    fun ticket(): WriteTicket = WriteTicket(name, shared.generation)

    private fun valid(ticket: WriteTicket): Boolean = ticket.name == name && ticket.generation == shared.generation

    /** 全部关闭记录的键。 */
    fun closedKeys(): Set<String> =
        runCatching {
            prefs.all.keys
                .filter { it.startsWith(NOTICE_PREFIX) }
                .map { it.removePrefix(NOTICE_PREFIX) }
                .toSet()
        }.getOrDefault(emptySet())

    /** 按票据与接受顺序记下一条关闭（同键去重）：票据之后清理过、[order] 不比该键已落盘的新都不写；返回是否落盘。 */
    fun addClosed(
        ticket: WriteTicket,
        order: Long,
        key: String,
        atMs: Long,
    ): Boolean =
        synchronized(shared.lock) {
            val prefKey = NOTICE_PREFIX + key
            valid(ticket) && newer(prefKey, order) && committed(prefKey, order, prefs.edit().putLong(prefKey, atMs).commit())
        }

    /** 立即接受并记下（测试与没有并发的调用方）。 */
    fun addClosed(
        ticket: WriteTicket,
        key: String,
        atMs: Long,
    ): Boolean = addClosed(ticket, reserve(), key, atMs)

    /** [sectionId] 在 [nowMs] 所在的上海自然日已弹出的次数。 */
    fun shownToday(
        sectionId: String,
        nowMs: Long,
    ): Int {
        val raw = runCatching { prefs.getString(POPUP_PREFIX + sectionId, null) }.getOrNull() ?: return 0
        val day = raw.substringBefore(SEPARATOR)
        return if (day == PopupPolicy.shanghaiDay(nowMs)) raw.substringAfter(SEPARATOR).toIntOrNull() ?: 0 else 0
    }

    /** 按票据与接受顺序在真正显示后 +1（G14 ⑤）：票据之后清理过、[order] 不比该键已落盘的新（旧日期的计数晚到）都不写；返回是否落盘。 */
    fun markShown(
        ticket: WriteTicket,
        order: Long,
        sectionId: String,
        nowMs: Long,
    ): Boolean =
        synchronized(shared.lock) {
            val prefKey = POPUP_PREFIX + sectionId
            if (!valid(ticket) || !newer(prefKey, order)) return@synchronized false
            val next = shownToday(sectionId, nowMs) + 1
            committed(prefKey, order, prefs.edit().putString(prefKey, "${PopupPolicy.shanghaiDay(nowMs)}$SEPARATOR$next").commit())
        }

    /** 立即接受并 +1（测试与没有并发的调用方）。 */
    fun markShown(
        ticket: WriteTicket,
        sectionId: String,
        nowMs: Long,
    ): Boolean = markShown(ticket, reserve(), sectionId, nowMs)

    /** 撤回同意、切换环境：进锁先推进世代（之前的票据全部作废），再整体清；没落盘抛错（清理器据此保留待清理）。 */
    fun clearAll() {
        synchronized(shared.lock) {
            shared.generation += 1
            beforeClearCommit()
            if (!prefs.edit().clear().commit()) throw IOException("home prefs not cleared")
        }
    }

    companion object {
        /** couli_home_prefs：故意不以 couli. 开头（不随环境切换的命名空间清理），由首页清理器自己清。 */
        const val NAME = "couli_home_prefs"
        private const val NOTICE_PREFIX = "n:"
        private const val POPUP_PREFIX = "p:"
        private const val SEPARATOR = "|"
        private val SHARED = ConcurrentHashMap<String, Shared>()
    }
}
