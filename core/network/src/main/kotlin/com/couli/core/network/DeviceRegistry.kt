package com.couli.core.network

import com.couli.core.clientcore.DeviceIdPolicy
import com.couli.core.clientcore.PrivacyStateRaw
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
) : DeviceCredentials {
    /** 凭据丢失（10402 后重新注册失败）：App 接到 PrivacyGate 重新开始退避注册。 */
    @Volatile
    var onCredentialsLost: () -> Unit = {}

    /** 单飞注册锁（注册请求在锁内，可能等网络；只在后台线程上取）。 */
    private val registerLock = Any()

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

    /** 本清理世代里每个被拒 device_id 的重新注册结果（R1-04；键可为 null：被拒请求没带 device_id）。只在 [stateLock] 下读写。 */
    private val reRegisterResults =
        object : LinkedHashMap<String?, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String?, Boolean>?): Boolean = size > RESULT_MEMORY
        }

    override val deviceId: String? get() = current()?.deviceId

    override val installSecret: String? get() = current()?.installSecret

    /** 是否已有可用的凭据（读不出按没有）。 */
    val registered: Boolean get() = current() != null

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

    private fun current(): StoredDevice.Present? =
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
                StoredDevice.None -> register(ticket)
            }
        }

    /**
     * 10402：重新注册 1 次（单飞）。[usedDeviceId] 是被拒请求带的 device_id。等锁期间已换成别的 device_id 的直接成功；本清理世代里
     * 同一个被拒 device_id 已有结果的（成功或失败）直接复用。阻塞，在 ApiClient 的调用线程（普通请求）或刷新线程上执行。
     */
    @Suppress("ReturnCount")
    fun reRegister(usedDeviceId: String?): Boolean {
        val ticket = ticket()
        return synchronized(registerLock) {
            if (privacy() != PrivacyStateRaw.CONSENTED || !owns(ticket)) return false
            val now = current()?.deviceId
            if (now != null && now != usedDeviceId) return true
            // 单飞：同一个被拒 device_id 的重新注册已经结束 → 复用那次的完整结果（含失败），不再注册第 2 次
            synchronized(stateLock) { if (reRegisterResults.containsKey(usedDeviceId)) return reRegisterResults[usedDeviceId] == true }
            // 被拒的 device_id 先在内存里作废（这次注册请求也不带它），再删旧凭据：删不掉不注册（旧凭据还在存储里）
            synchronized(stateLock) {
                if (!ownsLocked(ticket)) return false
                if (usedDeviceId != null) revokedDeviceId = usedDeviceId
            }
            val ok = runCatching { removeStored(ticket) }.getOrDefault(false) && register(ticket)
            // 结果只记进同一清理世代；归属已失效（撤回、切换环境）的不记、不报凭据丢失
            val recorded =
                synchronized(stateLock) {
                    ownsLocked(ticket).also { owned -> if (owned) reRegisterResults[usedDeviceId] = ok }
                }
            if (!ok && recorded) onCredentialsLost()
            ok && recorded
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
            store.clear()
        }
    }

    private fun storedNow(): StoredDevice =
        synchronized(stateLock) {
            current()?.let { return it }
            if (loaded) StoredDevice.None else StoredDevice.Unreadable
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
    private fun register(ticket: Ticket): Boolean {
        val first = readHash(ticket) ?: return false
        when (send(first, ticket)) {
            Outcome.Saved -> return true
            Outcome.Failed -> return false
            Outcome.RereadHash -> if (!rejectHash(first.hash, ticket)) return false
        }
        // 重读一次设备标识；读到本世代被拒过的值就不发（不带原值重试），按退避再读
        val again = readHash(ticket) ?: return false
        return when (send(again, ticket)) {
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
    private fun readHash(ticket: Ticket): Hashed? {
        hooks.beforeIdRead()
        val raw =
            synchronized(stateLock) {
                if (!ownsLocked(ticket)) return null
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
    ): Outcome {
        val body = JsonObject(mapOf("device_hash" to JsonPrimitive(hashed.hash), "id_source" to JsonPrimitive(hashed.source))).toString()
        hooks.beforeSend()
        // R2-02：请求带上本任务的原始归属：ApiClient 按开始时的世代绑定，写出前的守卫再核对清理世代与隐私（不重新绑定到当前环境）
        val call = ApiCall(REGISTER_DEVICE, body = body, ownership = CallOwnership(ticket.epochs) { owns(ticket) })
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

        /** 记住的重新注册结果条数（每条对应一个被拒的 device_id）。 */
        const val RESULT_MEMORY = 8
    }
}
