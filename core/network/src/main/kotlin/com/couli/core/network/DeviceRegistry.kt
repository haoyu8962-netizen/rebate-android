package com.couli.core.network

import com.couli.core.clientcore.DeviceIdPolicy
import com.couli.core.clientcore.PrivacyStateRaw
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 读设备原始标识（Android：ANDROID_ID）。只在已同意隐私政策之后调用（BR-ID-11：同意前禁止读取）。 */
fun interface DeviceIdReader {
    fun read(): String?
}

/** 一次设备凭据读取：没有、读到、读取失败（不能当成没有，否则会重复注册出新设备）。 */
sealed interface StoredDevice {
    data object None : StoredDevice

    data class Present(
        val deviceId: String,
        val installSecret: String,
    ) : StoredDevice

    data object Unreadable : StoredDevice
}

/** device_id 与 install_secret 的存储（install_secret 只进安全存储，CSB-05）。 */
interface DeviceCredentialStore {
    fun read(): StoredDevice

    /** 两项一起写；写不进抛错。 */
    fun save(
        deviceId: String,
        installSecret: String,
    )

    /** 两项一起删；删不掉抛错。 */
    fun clear()
}

/** 内存实现（测试）。 */
class MemoryDeviceCredentialStore : DeviceCredentialStore {
    @Volatile
    var stored: StoredDevice = StoredDevice.None

    override fun read(): StoredDevice = stored

    override fun save(
        deviceId: String,
        installSecret: String,
    ) {
        stored = StoredDevice.Present(deviceId, installSecret)
    }

    override fun clear() {
        stored = StoredDevice.None
    }
}

/**
 * Keystore 加密存储（[EncryptedPreferences]，couli.session）：键名沿用 APP-02b 约定的 device_id、install_secret（撤回同意的占位清理
 * 按键名删，切换环境时整个命名空间清、Keystore 密钥删除）。任一项读取失败 → [StoredDevice.Unreadable]；内容损坏或只剩一项 →
 * 尽力删除后按没有（重新注册）。
 */
class KeystoreDeviceCredentialStore(
    private val storage: EncryptedPreferences,
) : DeviceCredentialStore {
    override fun read(): StoredDevice {
        val id = storage.read(KEY_DEVICE_ID)
        val secret = storage.read(KEY_INSTALL_SECRET)
        return when {
            id == StoredValue.Failed || secret == StoredValue.Failed -> {
                StoredDevice.Unreadable
            }

            id is StoredValue.Present && secret is StoredValue.Present -> {
                StoredDevice.Present(id.text, secret.text)
            }

            id == StoredValue.Absent && secret == StoredValue.Absent -> {
                StoredDevice.None
            }

            else -> {
                runCatching { storage.remove(KEY_DEVICE_ID, KEY_INSTALL_SECRET) }
                StoredDevice.None
            }
        }
    }

    override fun save(
        deviceId: String,
        installSecret: String,
    ) = storage.putAll(mapOf(KEY_DEVICE_ID to deviceId, KEY_INSTALL_SECRET to installSecret))

    override fun clear() = storage.remove(KEY_DEVICE_ID, KEY_INSTALL_SECRET)

    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_INSTALL_SECRET = "install_secret"
    }
}

/** [DeviceRegistry] 的测试时序钩子：生产全是空操作。都在锁外调用。 */
internal class DeviceRegistryHooks(
    /** 读取设备标识的同步区之前（上一次归属核对之后）。 */
    val beforeIdRead: () -> Unit = {},
    /** 读取设备标识之后、读后归属核对之前。 */
    val afterIdRead: () -> Unit = {},
    /** 交给 ApiClient 之前（读后归属核对之后）。 */
    val beforeSend: () -> Unit = {},
)

/**
 * 设备注册（BR-ID-09；03 §4.1、§4.2）：实现 [DeviceCredentials]（签名用的 device_id、install_secret）与 10402 的重新注册。
 *
 * - **归属核对**（APP-03b R1-02）：每次注册（[registerAsync]、[ensureRegistered]、[reRegister]）在被调用时就记下归属 [Ticket]：
 *   隐私 / 环境世代（InFlightCalls.epochs）与本对象的清理世代（[clear] 加 1）。注册的**每一个结果分支**——成功保存、20001 记下
 *   被拒哈希并重读、10402 记下重新注册结果、删除旧凭据——都先在 [stateLock] 下核对归属仍有效且仍已同意；失效的结果不改任何状态、
 *   不重读标识、不继续注册（撤回同意、切换环境之后晚到的结果一律丢弃）。
 * - [registerAsync]：PrivacyGate.onConsented 的设备注册步骤（退避重试由 PrivacyGate 负责：失败回调 false）。先判隐私（只在已同意且
 *   凭据可读时，BR-ID-11：同意前连标识都不读），已有凭据直接成功；凭据读不出不注册（避免重复注册出新设备）。然后读 ANDROID_ID，
 *   按 [DeviceIdPolicy] 判定：无效值（空、全零、已知公共固定值）不注册、不发请求、不求哈希上报，按退避再读。有效值求 device_hash，
 *   调 `POST /v1/devices`（id_source = android_id），成功后**先写安全存储、再改内存**。
 * - 20001 且 data.fields 含 device_hash（Effect reread_device_hash）：不提示用户，重读一次设备标识再注册。**每次**收到 20001 都把
 *   这次发出的哈希记进本世代的被拒集合（含重读之后那次，R1-03）；本世代里被拒过的值一律不再发（不带原值重试），按退避再读。
 *   被拒集合只记在本进程、随 [clear] 清空（TODO(N-21)：是否跨进程记住未定）。
 * - [reRegister]（10402，ApiClient 的 NetworkHooks.reRegisterDevice；普通请求与刷新请求共用这一个入口）：单飞——同一把注册锁；
 *   按被拒的 device_id 记下完整结果（成功或失败，R1-04），同一清理世代里晚到的、带同一个旧 device_id 的 10402 直接复用，不再注册
 *   第 2 次、不再触发 [onCredentialsLost]；等锁期间已换成别的 device_id 的直接成功。被拒的旧 device_id 立即在内存里作废（之后的
 *   请求不再带它，包括这次注册请求），先删旧凭据再注册；失败时调一次 [onCredentialsLost]（App 接到 PrivacyGate 重新开始退避注册，
 *   之后的尝试统一交给退避流程）。
 * - [clear]：撤回同意、切换环境的清理项（先清内存、作废在途注册与本世代的被拒记录，再删存储；删不掉抛错，由清理入口重试）。
 * - **任务归属**（APP-03b R2-01、R2-02）：每个注册任务开始时绑定一个 [Ticket]（隐私、环境、清理三种世代）。读取设备标识、发送
 *   请求、保存结果每一步都在 [stateLock]（与 [clear] 共用）里核对：标识读取与读前核对在同一同步区，撤回完成后旧任务不能再读；
 *   请求带原始归属（[CallOwnership]），ApiClient 按开始时的世代绑定并在写出前再核对，撤回、切换环境（InFlightCalls 取消在途
 *   请求）之后不会发到新环境。
 * - **调用方有效性**（APP-04 Android 第 3 轮评审 R3-03、共性第 58 条）：[reRegister] 带上触发它的调用方（[RecoveryCaller]：有效性与
 *   剩余预算），登记进本次单飞窗口。取得注册锁后、读取设备标识前、注册请求实际写出前都核对「窗口里还有有效的调用方」；写出之后
 *   最后一个调用方失效即取消在途请求。共享注册仍有别的有效调用方时照常继续；一个都没有了就停止，不记结果——但被拒的凭据已经隔离、
 *   当前没有可用凭据的，仍按清理世代交给唯一的退避链一次（设备恢复，与 iOS 同）。
 * - **共享窗口与各自的预算**（APP-04 共性第 64 条，Android APP-05a 第 1 轮评审 R1-07）：同一被拒 device_id、同一归属世代的重新注册
 *   只有一个共享任务，在 [reRegisterExecutor] 上执行，用 ApiClient 对 registerDevice 的独立网络超时，不带任何调用方的截止时刻；
 *   每个调用方只在自己的预算与有效性内等待共享结果，到期或失效就自己返回 false（并离开窗口），不影响仍有效的调用方。
 *
 * 注册请求是阻塞的网络调用，在 [executor]（后台线程）上执行；[deviceId] / [installSecret] 只读内存，不碰存储以外的东西。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 端别、标识、存储、隐私、网络、世代逐项注入（单测替身）
class DeviceRegistry(
    private val platform: String,
    private val idReader: DeviceIdReader,
    private val store: DeviceCredentialStore,
    /** [PrivacyStateRaw]：已同意且撤回清理已完成时为 consented。 */
    private val privacy: () -> String,
    /** 网络核心（与 ApiClient 相互依赖，延迟取）。 */
    private val client: () -> ApiClient,
    /** 隐私 / 环境世代（InFlightCalls.epochs）：注册开始后变了（撤回同意、切换环境）就丢弃结果。 */
    private val epochs: () -> CallEpochs,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "couli-device-register").apply { isDaemon = true } },
    /** 共享重新注册任务的线程（与 [executor] 分开：调用方各自等待，任务不占用任何调用方的线程）。 */
    private val reRegisterExecutor: Executor =
        Executors.newCachedThreadPool { Thread(it, "couli-device-reregister").apply { isDaemon = true } },
    /** 环境已就绪（EnvironmentGate.isSettled：启动对账成功且不在运行中切换，F-X-03 ③）。缺省按就绪（单测）。 */
    private val environmentSettled: () -> Boolean = { true },
    /** 凭据读取屏障（F-X-03；App 里与 ApiClient、撤回同意、环境切换共用一份）。 */
    private val readBarrier: CredentialReadBarrier = CredentialReadBarrier(),
) : DeviceCredentials {
    /** 凭据丢失（10402 后重新注册失败）：App 接到 PrivacyGate 重新开始退避注册。 */
    @Volatile
    var onCredentialsLost: () -> Unit = {}

    /** 单飞注册锁（注册请求在锁内，可能等网络；只在后台线程上取）。 */
    private val registerLock = Any()

    /** 正在 [reRegister] 里（等锁或持锁）的调用方（R3-03）；读写都在它自己的同步区里。 */
    private val recoveryCallers = mutableListOf<RecoveryCaller>()

    /** 单飞窗口里还有有效的调用方。 */
    private fun hasLiveCaller(): Boolean = synchronized(recoveryCallers) { recoveryCallers.any { it.valid() } }

    /** 共享重新注册窗口的键：被拒的 device_id 与归属世代（撤回、切换环境、清理之后的调用不加入旧窗口）。 */
    private data class WindowKey(
        val usedDeviceId: String?,
        val epochs: CallEpochs,
        val clear: Long,
    )

    /** 进行中的共享重新注册（R1-07）。只在 recoveryCallers 的同步区里读写。 */
    private val windows = mutableMapOf<WindowKey, CompletableFuture<Boolean>>()

    /** 测试用的时序钩子（R2-01、R2-02：在读取标识、发送请求的前后暂停，复现与撤回、切换环境的交错）。 */
    internal var hooks: DeviceRegistryHooks = DeviceRegistryHooks()

    /** 内存状态锁（不等网络；[clear] 在主线程上取；读取设备标识也在这把锁里，R2-01）。 */
    private val stateLock = Any()
    private var cached: StoredDevice.Present? = null
    private var loaded = false

    /** 被服务端拒绝（10402）的 device_id：内存里作废，不再带出。 */
    private var revokedDeviceId: String? = null

    /** [clear] 加 1：之前开始的注册的任何结果都不再生效。 */
    private var clearGeneration = 0L

    /** 本清理世代里 20001 拒收过的 device_hash（不带原值重试，R1-03）。只在 [stateLock] 下读写。 */
    private val rejectedHashes = mutableSetOf<String>()

    /** 本清理世代里已登记过恢复（交给退避链）的被拒 device_id（R1-02）。只在 [stateLock] 下读写，随 [clear] 清空。 */
    private val recoveryClaimed = mutableSetOf<String?>()

    /** 本清理世代里每个被拒 device_id 的重新注册结果（R1-04；键可为 null：被拒请求没带 device_id）。只在 [stateLock] 下读写。 */
    private val reRegisterResults =
        object : LinkedHashMap<String?, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String?, Boolean>?): Boolean = size > RESULT_MEMORY
        }

    override val deviceId: String? get() = current()?.deviceId

    override val installSecret: String? get() = current()?.installSecret

    /** 是否已有可用的凭据（读不出、此刻不可读都按没有）。 */
    val registered: Boolean get() = current() != null

    /**
     * 设备凭据此刻可读（F-X-03 ③，对照鸿蒙 FU-HM-0711b R2-01）：本对象的隐私口径为 consented（已同意且撤回清理已完成）且环境已就绪。
     * 不成立时缓存与存储都不碰：[deviceId] / [installSecret] 为 null、[registered] 为 false（埋点不接受事件、诊断省略 device_id）；注册、
     * 重新注册不读、不删、不发（交给退避）。所有读取入口都经 [current] / [storedNow] 在读取屏障里现判。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun credentialsReadable(): Boolean =
        try {
            privacy() == PrivacyStateRaw.CONSENTED && environmentSettled()
        } catch (error: Exception) {
            false
        }

    /** 一次注册的归属：被调用时的隐私 / 环境世代与清理世代。 */
    private class Ticket(
        val epochs: CallEpochs,
        val clear: Long,
    )

    private fun ticket(): Ticket = synchronized(stateLock) { Ticket(epochs(), clearGeneration) }

    /** 在 [stateLock] 下：归属仍有效（世代都没变）且仍已同意。 */
    private fun ownsLocked(ticket: Ticket): Boolean =
        epochs() == ticket.epochs && clearGeneration == ticket.clear && privacy() == PrivacyStateRaw.CONSENTED

    private fun owns(ticket: Ticket): Boolean = synchronized(stateLock) { ownsLocked(ticket) }

    /** 当前可用的凭据：在读取屏障里现判可读（[credentialsReadable]）才访问缓存与存储；不可读为 null（不读）。 */
    private fun current(): StoredDevice.Present? = readBarrier.read(::credentialsReadable, { null }) { currentLocked() }

    private fun currentLocked(): StoredDevice.Present? =
        synchronized(stateLock) {
            if (!loaded) {
                when (val stored = store.read()) {
                    is StoredDevice.Present -> {
                        cached = stored
                        loaded = true
                    }

                    // 没有：之后注册成功再写内存
                    StoredDevice.None -> {
                        loaded = true
                    }

                    // 读不出：下次再读，不当成没有
                    StoredDevice.Unreadable -> {
                        Unit
                    }
                }
            }
            cached?.takeIf { it.deviceId != revokedDeviceId }
        }

    /**
     * PrivacyGate 的设备注册步骤：在后台注册，[onResult] 回调是否成功（失败由 PrivacyGate 退避重试）。归属在调用时记下：排队期间
     * 撤回同意、切换环境的，轮到时不再注册。
     */
    fun registerAsync(onResult: (Boolean) -> Unit) {
        val ticket = ticket()
        executor.execute { onResult(runCatching { ensureRegistered(ticket) }.getOrDefault(false)) }
    }

    /** 阻塞：确保已注册（已有凭据直接返回 true）。不在主线程调用。 */
    fun ensureRegistered(): Boolean = ensureRegistered(ticket())

    private fun ensureRegistered(ticket: Ticket): Boolean =
        synchronized(registerLock) {
            if (privacy() != PrivacyStateRaw.CONSENTED || !owns(ticket)) return false
            when (storedNow()) {
                is StoredDevice.Present -> true
                StoredDevice.Unreadable -> false
                StoredDevice.None -> register(ticket, ALWAYS)
            }
        }

    /**
     * 10402：重新注册 1 次（单飞）。[usedDeviceId] 是被拒请求带的 device_id。等锁期间已换成别的 device_id 的直接成功；本清理世代里
     * 同一个被拒 device_id 已有结果的（成功或失败）直接复用。阻塞，在 ApiClient 的调用线程（普通请求）或刷新线程上执行。
     * [caller]：触发这次重新注册的调用方（R3-03）；窗口里一个有效调用方都没有时停止（见类注释）。
     */
    fun reRegister(
        usedDeviceId: String?,
        caller: RecoveryCaller = RecoveryCaller.UNBOUNDED,
    ): Boolean {
        val ticket = ticket()
        // F-ALL-08（共性第 21 条）：被拒的 device_id 在等单飞锁之前就隔离，等锁期间别的请求也不再带它
        quarantine(usedDeviceId, ticket)
        val key = WindowKey(usedDeviceId, ticket.epochs, ticket.clear)
        var started: CompletableFuture<Boolean>? = null
        val shared =
            synchronized(recoveryCallers) {
                // 先登记为窗口调用方，再加入或发起共享任务：任务开始核对时一定看得到这个调用方（共性第 61 条）
                recoveryCallers += caller
                windows[key]?.takeIf { !it.isDone } ?: CompletableFuture<Boolean>().also {
                    windows[key] = it
                    started = it
                }
            }
        started?.let { future -> launchShared(key, ticket, future) }
        return try {
            awaitShared(shared, caller)
        } finally {
            synchronized(recoveryCallers) { recoveryCallers -= caller }
        }
    }

    /** 在 [reRegisterExecutor] 上执行共享重新注册（单飞锁内），结果交给窗口里的全部调用方。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何异常都按失败结束窗口
    private fun launchShared(
        key: WindowKey,
        ticket: Ticket,
        future: CompletableFuture<Boolean>,
    ) {
        val task =
            Runnable {
                val ok =
                    try {
                        synchronized(registerLock) { reRegisterLocked(key.usedDeviceId, ticket) }
                    } catch (error: Exception) {
                        false
                    }
                synchronized(recoveryCallers) { windows.remove(key, future) }
                future.complete(ok)
            }
        try {
            reRegisterExecutor.execute(task)
        } catch (error: Exception) {
            synchronized(recoveryCallers) { windows.remove(key, future) }
            future.complete(false)
        }
    }

    /**
     * 调用方各自的等待（共性第 64 条）：共享任务结束就取结果；调用方自己的截止时刻到了或失效了就返回 false（离开窗口），共享任务照常
     * 为别的有效调用方继续——只有窗口里全部调用方都失效，写出前的核对与在途请求的取消才会让它停下。
     */
    @Suppress("SwallowedException", "ReturnCount") // 等待超时只是再核对一次调用方；结束、失效、各类等待结果逐项返回
    private fun awaitShared(
        shared: CompletableFuture<Boolean>,
        caller: RecoveryCaller,
    ): Boolean {
        while (true) {
            if (shared.isDone) return shared.getNow(false)
            if (!caller.valid()) return false
            try {
                return shared.get(CALLER_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                continue
            } catch (error: ExecutionException) {
                return false
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    @Suppress("ReturnCount")
    private fun reRegisterLocked(
        usedDeviceId: String?,
        ticket: Ticket,
    ): Boolean {
        if (privacy() != PrivacyStateRaw.CONSENTED || !owns(ticket)) return false
        // 凭据此刻不可读（环境未就绪，F-X-03 ③）：不读、不删旧凭据、不注册
        if (!credentialsReadable()) return false
        val now = current()?.deviceId
        if (now != null && now != usedDeviceId) return true
        // 单飞：同一个被拒 device_id 的重新注册已经结束 → 复用那次的完整结果（含失败），不再注册第 2 次
        synchronized(stateLock) { if (reRegisterResults.containsKey(usedDeviceId)) return reRegisterResults[usedDeviceId] == true }
        // R3-03 第一处：取得注册锁后，窗口里已没有有效调用方 → 不注册；被拒凭据已隔离的交给退避链一次
        if (!hasLiveCaller()) return abandon(usedDeviceId, ticket)
        // 被拒的 device_id 先在内存里作废（这次注册请求也不带它），再删旧凭据：删不掉不注册（旧凭据还在存储里）
        synchronized(stateLock) {
            if (!ownsLocked(ticket)) return false
            if (usedDeviceId != null) revokedDeviceId = usedDeviceId
        }
        val ok = runCatching { removeStored(ticket) }.getOrDefault(false) && register(ticket, ::hasLiveCaller)
        // 注册途中最后一个调用方失效（读标识前、写出前被拦下，或在途请求被取消）：不记结果，交给退避链一次
        if (!ok && !hasLiveCaller()) return abandon(usedDeviceId, ticket)
        return settle(usedDeviceId, ticket, ok)
    }

    /** 记下一次重新注册的结果（单飞复用）；失败时通知一次凭据丢失。 */
    private fun settle(
        usedDeviceId: String?,
        ticket: Ticket,
        ok: Boolean,
    ): Boolean {
        // 结果只记进同一清理世代；归属已失效（撤回、切换环境）的不记、不报凭据丢失
        val recorded =
            synchronized(stateLock) {
                ownsLocked(ticket).also { owned -> if (owned) reRegisterResults[usedDeviceId] = ok }
            }
        // 退避通知与 [recoverAfterRejection] 共用同一份「已登记恢复」状态：同一被拒凭据在同一清理世代里只发一次
        if (!ok && recorded) notifyLostOnce(usedDeviceId, ticket)
        return ok && recorded
    }

    /**
     * 单飞窗口里已没有有效调用方（R3-03）：停止这条恢复链、不记结果（之后有效的调用方仍可再注册）。被拒的凭据已经隔离、当前没有可用
     * 凭据的，按清理世代登记一次恢复、交给唯一的退避链（设备恢复不依赖页面调用方，与 iOS 同）。返回 false。
     */
    private fun abandon(
        usedDeviceId: String?,
        ticket: Ticket,
    ): Boolean {
        val needed =
            synchronized(stateLock) {
                ownsLocked(ticket) && credentialsReadable() && current() == null &&
                    (usedDeviceId == null || revokedDeviceId == usedDeviceId)
            }
        if (needed) notifyLostOnce(usedDeviceId, ticket)
        return false
    }

    /**
     * 有效世代的 10402（ApiClient 的 NetworkHooks.quarantineDevice，含重放里再次收到的）：这次请求带的 device_id 仍是当前凭据时
     * 立刻在内存里作废（之后的请求、包括注册请求都不再带它）。不发起注册：恢复只走 [reRegister] 与 PrivacyGate 的退避链。
     * 返回 true（便于在条件表达式里串联）。
     */
    fun quarantine(usedDeviceId: String?): Boolean {
        quarantine(usedDeviceId, ticket())
        return true
    }

    /**
     * 重放里再次收到 10402、重放预算已用完（ApiClient 的 NetworkHooks.recoverDevice；APP-03b 共性第 25 条的补全，APP-04 Android
     * 第 1 轮评审 R1-02）：这次请求带的 device_id 已被隔离、当前也没有别的可用凭据时，按清理世代登记一次恢复，调 [onCredentialsLost]
     * 启动唯一的退避注册链（App 接到 PrivacyGate.restartDeviceRegistration：作废之前排着的重试、从第 1 次开始）。
     * 同一被拒凭据在同一清理世代里只登记一次（与 [reRegister] 的失败通知共用状态）；归属已失效（撤回、切换环境）、凭据已被别的注册
     * 替换的不登记。
     */
    fun recoverAfterRejection(usedDeviceId: String?) {
        val ticket = ticket()
        val needed =
            synchronized(stateLock) {
                ownsLocked(ticket) && credentialsReadable() && current() == null &&
                    (usedDeviceId == null || revokedDeviceId == usedDeviceId)
            }
        if (needed && claimRecovery(usedDeviceId, ticket)) onCredentialsLost()
    }

    /** 重新注册失败：同一被拒凭据在本清理世代里还没登记过恢复时，通知一次凭据丢失（启动退避链）。 */
    private fun notifyLostOnce(
        usedDeviceId: String?,
        ticket: Ticket,
    ) {
        if (claimRecovery(usedDeviceId, ticket)) onCredentialsLost()
    }

    /** 登记「这个被拒凭据的恢复已经交给退避链」；本清理世代里第一次登记返回 true。 */
    private fun claimRecovery(
        usedDeviceId: String?,
        ticket: Ticket,
    ): Boolean =
        synchronized(stateLock) {
            ownsLocked(ticket) && recoveryClaimed.add(usedDeviceId)
        }

    private fun quarantine(
        usedDeviceId: String?,
        ticket: Ticket,
    ) {
        if (usedDeviceId == null) return
        synchronized(stateLock) {
            if (ownsLocked(ticket) && current()?.deviceId == usedDeviceId) revokedDeviceId = usedDeviceId
        }
    }

    /** 撤回同意、切换环境：先清内存并作废在途注册，再删存储（删不掉抛错，内存里已经没有）。 */
    fun clear() {
        synchronized(stateLock) {
            clearGeneration += 1
            cached = null
            loaded = true
            revokedDeviceId = null
            rejectedHashes.clear()
            reRegisterResults.clear()
            recoveryClaimed.clear()
            store.clear()
        }
    }

    /** 存储里的凭据：此刻不可读（F-X-03 ③）按读不出（不注册，免得多注册一个设备）。 */
    private fun storedNow(): StoredDevice =
        readBarrier.read(::credentialsReadable, { StoredDevice.Unreadable }) {
            synchronized(stateLock) {
                currentLocked() ?: if (loaded) StoredDevice.None else StoredDevice.Unreadable
            }
        }

    /** 删旧凭据（10402）：归属已失效的不删。删不掉抛错。 */
    private fun removeStored(ticket: Ticket): Boolean =
        synchronized(stateLock) {
            if (!ownsLocked(ticket)) return false
            store.clear()
            cached = null
            loaded = true
            true
        }

    /**
     * 在 [registerLock] 下：读标识 → 判定 → 注册；20001（reread_device_hash）时记下被拒的哈希、重读一次再注册。每一步之前都核对归属。
     */
    @Suppress("ReturnCount")
    private fun register(
        ticket: Ticket,
        live: () -> Boolean,
    ): Boolean {
        val first = readHash(ticket, live) ?: return false
        when (send(first, ticket, live)) {
            Outcome.Saved -> return true
            Outcome.Failed -> return false
            Outcome.RereadHash -> if (!rejectHash(first.hash, ticket)) return false
        }
        // 重读一次设备标识；读到本世代被拒过的值就不发（不带原值重试），按退避再读
        val again = readHash(ticket, live) ?: return false
        return when (send(again, ticket, live)) {
            Outcome.Saved -> {
                true
            }

            Outcome.Failed -> {
                false
            }

            Outcome.RereadHash -> {
                // 重读后的值同样被拒：也记下，退避重试时不再发它（R1-03）
                rejectHash(again.hash, ticket)
                false
            }
        }
    }

    /** 记下 20001 拒收的哈希；归属已失效（R1-02）返回 false，什么都不改。 */
    private fun rejectHash(
        hash: String,
        ticket: Ticket,
    ): Boolean =
        synchronized(stateLock) {
            if (!ownsLocked(ticket)) return false
            rejectedHashes += hash
            true
        }

    private class Hashed(
        val hash: String,
        val source: String,
    )

    /**
     * 读一次标识并判定；归属失效、无效、本世代被拒过的值为 null（不求哈希上报、不发请求）。
     *
     * R2-01：最后一次归属与隐私核对和标识读取本身在同一个 [stateLock] 同步区里（[clear] 也取这把锁）：撤回同意或切换环境的清理一旦
     * 执行过 [clear]，旧任务就不能再开始读取 ANDROID_ID；读取进行中的 [clear] 等读取结束后才作废本任务。
     * R2-02：读取结束后在同一把锁里再核对一次归属，失效就立即结束（不求哈希、不发请求）。
     */
    @Suppress("ReturnCount") // 未同意、无效、被拒的值逐项不发
    private fun readHash(
        ticket: Ticket,
        live: () -> Boolean,
    ): Hashed? {
        hooks.beforeIdRead()
        val raw =
            synchronized(stateLock) {
                // R3-03 第二处：读取标识之前，单飞窗口里仍有有效的调用方
                if (!ownsLocked(ticket) || !live()) return null
                idReader.read()
            }
        hooks.afterIdRead()
        val decision = DeviceIdPolicy.evaluate(platform, raw, PrivacyStateRaw.CONSENTED)
        val hash = decision.deviceHash?.takeIf { decision.register } ?: return null
        synchronized(stateLock) { if (!ownsLocked(ticket) || hash in rejectedHashes) return null }
        return Hashed(hash, checkNotNull(decision.idSource))
    }

    private enum class Outcome { Saved, Failed, RereadHash }

    @Suppress("ReturnCount")
    private fun send(
        hashed: Hashed,
        ticket: Ticket,
        live: () -> Boolean,
    ): Outcome {
        val body = JsonObject(mapOf("device_hash" to JsonPrimitive(hashed.hash), "id_source" to JsonPrimitive(hashed.source))).toString()
        hooks.beforeSend()
        // R2-02：请求带上本任务的原始归属：ApiClient 按开始时的世代绑定，写出前的守卫再核对清理世代与隐私（不重新绑定到当前环境）
        // R3-03 第三处：写出前的守卫同样核对「仍有有效调用方」，写出后窗口里最后一个调用方失效即取消在途请求。
        // 共性第 64 条（R1-07）：共享请求不带任何调用方的截止时刻，用 registerDevice 自己的网络超时；各调用方的预算只管它自己的等待
        val ownership = CallOwnership(ticket.epochs) { owns(ticket) && live() }
        val call = ApiCall(REGISTER_DEVICE, body = body, ownership = ownership)
        return when (val result = client().execute(call)) {
            is ApiResult.Success -> {
                val data = result.data as? JsonObject ?: return Outcome.Failed
                val deviceId = data.text("device_id")?.takeIf { it.isNotEmpty() } ?: return Outcome.Failed
                val secret = data.text("install_secret")?.takeIf { it.length in SECRET_LENGTH } ?: return Outcome.Failed
                if (save(deviceId, secret, ticket)) Outcome.Saved else Outcome.Failed
            }

            is ApiResult.Failure -> {
                if (result.effect.kind == ErrorEffectKind.REREAD_DEVICE_HASH) Outcome.RereadHash else Outcome.Failed
            }

            else -> {
                Outcome.Failed
            }
        }
    }

    /** 先写安全存储、再改内存；归属已失效（注册开始后撤回同意、切换环境或清理过）的，结果丢弃。 */
    private fun save(
        deviceId: String,
        secret: String,
        ticket: Ticket,
    ): Boolean =
        synchronized(stateLock) {
            if (!ownsLocked(ticket)) return false
            if (runCatching { store.save(deviceId, secret) }.isFailure) return false
            cached = StoredDevice.Present(deviceId, secret)
            loaded = true
            revokedDeviceId = null
            true
        }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val REGISTER_DEVICE = "registerDevice"

        /** RegisterDeviceData.install_secret：16–128 个字符（openapi）。 */
        val SECRET_LENGTH = 16..128

        /** 退避链自己的注册（[ensureRegistered]）：没有页面调用方，不受调用方有效性约束。 */
        val ALWAYS: () -> Boolean = { true }

        /** 调用方等待共享结果时核对自己预算与有效性的间隔。 */
        const val CALLER_POLL_MS = 20L

        /** 记住的重新注册结果条数（每条对应一个被拒的 device_id）。 */
        const val RESULT_MEMORY = 8
    }
}
