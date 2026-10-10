package com.couli.core.privacy

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** 本机的隐私决定（BR-ID-11）。取值与 specs/privacy-gate-vectors.json 的 decision 同名。 */
enum class PrivacyDecision(
    val raw: String,
) {
    NOT_DECIDED("not_decided"),
    CONSENTED("consented"),
    BASIC_MODE("basic_mode"),
    ;

    companion object {
        /** 不认识的取值（记录损坏）按「尚未决定」处理：重新弹首启弹窗，什么都不初始化（最保守）。 */
        fun fromRaw(raw: String?): PrivacyDecision = entries.firstOrNull { it.raw == raw } ?: NOT_DECIDED
    }
}

/** 当前要展示的弹窗：无 / 首启弹窗 / 二次说明。 */
enum class PromptStage(
    val raw: String,
) {
    NONE("none"),
    FIRST("first"),
    SECOND("second"),
}

/**
 * 闸门状态。[mustPrompt] = 现在必须展示 [promptStage] 那个弹窗。
 * 重新同意期间（隐私政策升级后的冷启动、基本模式点【开启完整功能】）[decision] 仍是原来的决定，直到用户在弹窗里做出新决定
 * （rule-gaps 第 1 条：临时表示未规定，保守地不改持久化记录）。
 */
data class PrivacyGateState(
    val decision: PrivacyDecision,
    val mustPrompt: Boolean,
    val promptStage: PromptStage,
) {
    val decisionRaw: String get() = decision.raw
    val promptStageRaw: String get() = promptStage.raw

    /** 已同意当前版本、没有弹窗：可以初始化 SDK、进入完整功能。 */
    val fullyConsented: Boolean get() = decision == PrivacyDecision.CONSENTED && !mustPrompt
}

/**
 * 「本机隐私决定」的持久化（Android 实现见 [SharedPreferencesPrivacyStore]）。
 * 字段是测试接线约定（PrivacyGateVectorTest 文件头），三端同名。
 */
interface PrivacyStore {
    /** [PrivacyDecision.raw]。 */
    var decisionRaw: String

    /** 同意时实际展示的包内隐私政策版本（十进制整数串，BR-ID-12）；未同意过为 null。 */
    var consentedVersion: String?

    /** 同意时的点击时间（毫秒），设备注册成功后作为 client_at 上报（BR-ID-12）；不是重试时间。 */
    var consentedAtMillis: Long?

    /** 基本模式（BR-ID-11「基本模式状态保存在本地」）。与 [decisionRaw] 矛盾时按基本模式处理。 */
    var basicMode: Boolean

    /**
     * 撤回同意的本地清理还没完成（R1-01）：撤回时先写 true，清理全部成功后才写回 false。为 true 时 [PrivacyGate]
     * 在冷启动、每次读取会话凭据前（[CredentialAccessGuard.readable]）、每次初始化前重试清理，清理完成前不允许读凭据、不初始化。
     *
     * 缺省实现不落盘（只给不关心撤回的测试替身用，测试接线约定的四个字段不变）；生产 [SharedPreferencesPrivacyStore] 落盘。
     * [PrivacyGate] 另在内存里记一份，缺省实现下同一进程内照样重试。
     */
    var withdrawalCleanupPending: Boolean
        get() = false
        set(
            @Suppress("UNUSED_PARAMETER") value,
        ) = Unit

    /**
     * 撤回事务（R2-01）：「待清理」标记 + 基本模式决定 + 清除同意记录（版本、时间）。生产 [SharedPreferencesPrivacyStore]
     * 必须在**同一次** commit 里原子写入，不存在「标记已落盘、基本模式还没落盘」的中间状态。
     * 缺省实现逐字段写（只给内存测试替身用）：先写标记——即使逐字段写到一半进程被杀，标记在盘上，
     * 冷启动 [PrivacyGate] 也会强制按基本模式处理并补写本事务。
     */
    fun commitWithdrawal() {
        withdrawalCleanupPending = true
        decisionRaw = PrivacyDecision.BASIC_MODE.raw
        basicMode = true
        consentedVersion = null
        consentedAtMillis = null
    }
}

/** 设备注册的退避重试调度（可注入，单测用假调度器驱动虚拟时间）。 */
fun interface RetryScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    )
}

/** 生产用：主线程 Handler 延时执行。Handler 在第一次调度时才创建（构造本对象不碰 Android 框架）。 */
class MainThreadRetryScheduler : RetryScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        handler.postDelayed({ action() }, delayMillis)
    }
}

/**
 * 隐私闸门（03 §4.1、BR-ID-11、BR-ID-12、BR-ID-13）：首启弹窗 → 二次说明 → 基本模式的状态迁移与持久化，
 * 以及**第三方 SDK 初始化的唯一入口** [onConsented]。自定义规则 sdk-init-only-in-privacy-gate 只放行本文件里的 SDK 初始化调用。
 *
 * - 冷启动（[coldStart]）：未决定 → 首启弹窗；已同意但同意的版本低于包内版本（整数比较，9 < 10）→ 本次冷启动重新首启弹窗
 *   （当次运行中不再重新判断，「当次运行不打断」）；基本模式 → 不弹（版本升级也不自动弹）。
 * - [agree]：先在本地记录同意（版本 = 包内版本，时间 = 点击时间），再由调用方调 [onConsented] 初始化。
 *   agree 不内部转调 [onConsented]（rule-gaps 最后一条：未规定，两步分开，调用方按 03 §4.4 顺序在环境就绪后调）。
 * - [disagree]：首启弹窗 → 二次说明；[stillDisagree]：二次说明 → 基本模式（持久化）。
 * - [requestFullFunction]：基本模式点【开启完整功能】或需同意的功能 → 重新首启弹窗。
 * - [withdraw]：撤回同意（BR-ID-13）：调 [withdrawalCleanup] 清本地令牌、device_id、install_secret → 基本模式（持久化）。
 *
 * 不是当前弹窗对应的操作（如没有弹窗时 [stillDisagree]）忽略不处理（rule-gaps 第 5 条：未规定，保守地不改状态）。
 * 非线程安全，在主线程使用。
 *
 * @param bundledPolicyVersion 包内隐私政策版本（十进制整数串）。TODO(N-16): 协议线上版本、实际展示版本与用户协议版本
 *   （BR-ID-12）的输入在 §2.1 API 之外，接入 /v1/config.legal 后补。
 */
@Suppress("TooManyFunctions") // 状态迁移逐项一个入口；APP-03b 加了异步设备注册步骤与重新开始注册
class PrivacyGate(
    private val store: PrivacyStore,
    private val clock: () -> Long,
    private val bundledPolicyVersion: String,
) {
    private val bundledVersionNumber: Long =
        requireNotNull(bundledPolicyVersion.toLongOrNull()) { "包内隐私政策版本必须是整数：$bundledPolicyVersion" }

    /** 撤回同意时的本地清理入口（生产接 [CredentialCleanerRegistry]）。没接线就撤回 = 漏删，直接失败。 */
    var withdrawalCleanup: () -> Unit = { error("PrivacyGate.withdrawalCleanup 未接线：撤回同意必须清本地令牌、device_id、install_secret") }

    /** 设备注册失败后的退避重试调度。 */
    var retryScheduler: RetryScheduler = MainThreadRetryScheduler()

    private var stage: PromptStage = store.recoverInterruptedWithdrawal().coldStartStage(bundledVersionNumber)

    /** 撤回清理的「待清理」记录与凭据读取闸（R1-01）：会话层读凭据前调 [CredentialAccessGuard.readable]。 */
    val credentialAccess: CredentialAccessGuard = CredentialAccessGuard(store) { withdrawalCleanup() }

    private val initSteps = mutableListOf<Pair<String, () -> Unit>>()

    /**
     * 设备注册步骤（可异步，APP-03b）：调用后回调是否成功；失败按退避重试。[registerInitStep] 登记的同步步骤（抛异常 = 失败）也包成它。
     */
    private var deviceStep: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    /** 本同意周期内是否已经初始化过（同一实例只初始化一次；撤回后重置）。 */
    private var initialized = false

    /** 设备注册代次：撤回同意、重新开始注册（[restartDeviceRegistration]）时加一，之前排着的设备注册重试作废。 */
    private var consentGeneration = 0

    // 三端 API 统一带 now（brief §2.1）；本端的状态迁移只有 agree 用到时间，其余操作不看时间。
    @Suppress("UnusedParameter")
    fun state(now: Long = clock()): PrivacyGateState =
        PrivacyGateState(decision = store.effectiveDecision(), mustPrompt = stage != PromptStage.NONE, promptStage = stage)

    /** 本机已同意的隐私政策版本（十进制整数串；撤回记录的 version，APP-12 G24 / H12）；没有同意记录为 null。只读。 */
    fun consentedVersion(): String? = store.consentedVersion

    /** 冷启动：按本地记录与包内版本决定是否弹首启弹窗。 */
    @Suppress("UnusedParameter")
    fun coldStart(now: Long = clock()) {
        // 盘上有「待清理」标记 = 上次撤回没做完（清理失败、进程被杀）：不论其他字段，先强制补写基本模式、清同意记录（R2-01），
        // 再重试清理（R1-01）；清理成功只删标记、不改基本模式，仍失败就留着标记，读凭据前再试
        store.recoverInterruptedWithdrawal()
        credentialAccess.readable()
        stage = store.coldStartStage(bundledVersionNumber)
    }

    /** 首启弹窗或二次说明里点【同意】：记录同意（版本 = 包内版本，时间 = [now]）。 */
    fun agree(now: Long = clock()) {
        if (stage == PromptStage.NONE) return
        // 先写版本与时间，最后写决定：中途进程被杀时留下的仍是「未同意」，不会误初始化。
        store.consentedVersion = bundledPolicyVersion
        store.consentedAtMillis = now
        store.basicMode = false
        store.decisionRaw = PrivacyDecision.CONSENTED.raw
        stage = PromptStage.NONE
    }

    /** 首启弹窗里点【不同意】：展示一次二次说明。 */
    @Suppress("UnusedParameter")
    fun disagree(now: Long = clock()) {
        if (stage == PromptStage.FIRST) stage = PromptStage.SECOND
    }

    /**
     * 二次说明里点【仍不同意】：进入并保存基本模式。拒绝新版政策不是撤回：本地令牌保留但不使用（BR-ID-12 细则示例），
     * 不调撤回清理；原同意版本保留（基本模式下不看它）。
     */
    @Suppress("UnusedParameter")
    fun stillDisagree(now: Long = clock()) {
        if (stage != PromptStage.SECOND) return
        store.persistBasicMode()
        stage = PromptStage.NONE
    }

    /** 基本模式点【开启完整功能】或需同意的功能：重新弹首启弹窗。已同意当前版本时不弹。 */
    fun requestFullFunction(now: Long = clock()) {
        if (state(now).fullyConsented) return
        stage = PromptStage.FIRST
    }

    /**
     * 撤回同意（BR-ID-13，隐私中心【撤回同意】；页面在 APP-12）：清本地令牌、device_id、install_secret，进入基本模式。
     *
     * 顺序（R1-01、R2-01）：「待清理」标记 + 基本模式 + 清同意记录在一次 commit 里原子落盘（[PrivacyStore.commitWithdrawal]），
     * 然后执行清理，全部成功才去掉标记（只删标记，基本模式不动）。commit 之前进程被杀 = 撤回没发生（同意与凭据都原样）；
     * commit 之后任何时刻进程被杀或清理失败，标记都在盘上：下次冷启动（构造 / [coldStart]）不论其他字段强制按基本模式处理并补写，
     * 每次读凭据前（[CredentialAccessGuard.readable]）、每次初始化前（[onConsented]）重试清理，直到成功；
     * 完成前 [CredentialAccessGuard.readable] 为 false、[onConsented] 不初始化。
     * 隐私决定照样记为基本模式（基本模式不带任何凭据、不初始化 SDK，比留在已同意更保守）；清理失败的错误抛给调用方。
     * 服务端撤回记录（设备级 accepted=false）由 APP-12 的撤回协调器（App AppMe.withdrawConsent）在调本入口之前发出（G24）。
     */
    @Suppress("UnusedParameter")
    fun withdraw(now: Long = clock()) {
        credentialAccess.beginWithdrawal()
        stage = PromptStage.NONE
        // 新的同意周期：撤回后再同意时重新注册设备（device_id 已清除）；旧周期的重试作废。
        consentGeneration += 1
        initialized = false
        credentialAccess.runCleanup()?.let { throw it }
    }

    /**
     * 登记一个初始化步骤。执行顺序固定为 [INIT_ORDER]（与登记顺序无关）；不在其中的名字排在后面、按登记顺序。
     * 生产步骤见 [registerProductionInitSteps]。
     */
    fun registerInitStep(
        name: String,
        action: () -> Unit,
    ) {
        initSteps.removeAll { it.first == name }
        initSteps += name to action
        if (name == DEVICE_REGISTER) deviceStep = { onResult -> onResult(runCatching(action).isSuccess) }
    }

    /**
     * 登记异步的设备注册步骤（APP-03b DeviceRegistry.registerAsync）：[action] 在后台注册，完成后回调是否成功（可在任意线程回调，
     * 只回调第一次）；失败按 [retryDelayMillis] 退避重试（调度经 [retryScheduler]，生产在主线程）。顺序仍按 [INIT_ORDER] 排第一。
     */
    fun registerDeviceStep(action: (onResult: (Boolean) -> Unit) -> Unit) {
        initSteps.removeAll { it.first == DEVICE_REGISTER }
        initSteps += DEVICE_REGISTER to {}
        deviceStep = action
    }

    /**
     * 设备凭据丢失后重新开始注册（10402 后重新注册失败、切换环境清掉了 device_id）：本同意周期已初始化且仍已同意时，作废之前排着的
     * 重试，从第 1 次开始按退避注册。还没初始化的不做（之后 [onConsented] 会注册）。在主线程调用。
     */
    fun restartDeviceRegistration(now: Long = clock()) {
        if (!initialized || !state(now).fullyConsented) return
        consentGeneration += 1
        attemptDeviceRegister(attempt = 1, generation = consentGeneration)
    }

    /**
     * **唯一的第三方 SDK 初始化入口**（03 §4.1、§3.5）：已同意当前版本时按 设备注册 → 推送 → 联盟 → 崩溃 → 微信 的顺序调用
     * 已登记的步骤。同一实例、同一同意周期只调用一次；设备注册失败（步骤抛异常）不阻塞其余步骤，并在后台按
     * 1s、2s、4s…上限 60s 退避重试，直到成功。其余步骤失败只吞掉，不重试（rule-gaps 第 5 条：未规定）。
     */
    fun onConsented(now: Long = clock()) {
        if (initialized || !state(now).fullyConsented) return
        // 上次撤回的凭据还没清干净：先重试清理，仍失败就不初始化（设备注册会读写 device_id，R1-01），下次再试
        if (!credentialAccess.readable()) return
        initialized = true
        val generation = consentGeneration
        orderedInitSteps(initSteps).forEach { (name, action) ->
            if (name == DEVICE_REGISTER) {
                attemptDeviceRegister(attempt = 1, generation = generation)
            } else {
                runCatching(action)
            }
        }
    }

    private fun attemptDeviceRegister(
        attempt: Int,
        generation: Int,
    ) {
        if (generation != consentGeneration || !state().fullyConsented || credentialAccess.cleanupPending) return
        val step = deviceStep ?: return
        val answered = AtomicBoolean(false)
        val onResult = { succeeded: Boolean ->
            if (!answered.getAndSet(true) && !succeeded) {
                retryScheduler.schedule(retryDelayMillis(attempt)) {
                    attemptDeviceRegister(attempt + 1, generation)
                }
            }
        }
        if (runCatching { step(onResult) }.isFailure) onResult(false)
    }

    companion object {
        const val DEVICE_REGISTER = "device_register"
        const val PUSH = "push"
        const val UNION = "union"
        const val CRASH = "crash"
        const val WECHAT = "wechat"

        /** 初始化顺序（BR-ID-11）。 */
        val INIT_ORDER: List<String> = listOf(DEVICE_REGISTER, PUSH, UNION, CRASH, WECHAT)

        private const val FIRST_RETRY_DELAY_MILLIS = 1_000L
        private const val MAX_RETRY_DELAY_MILLIS = 60_000L

        /** 第 [failedAttempts] 次失败后的等待：1s、2s、4s…上限 60s。 */
        fun retryDelayMillis(failedAttempts: Int): Long {
            var delay = FIRST_RETRY_DELAY_MILLIS
            repeat((failedAttempts - 1).coerceAtLeast(0)) {
                if (delay >= MAX_RETRY_DELAY_MILLIS) return MAX_RETRY_DELAY_MILLIS
                delay *= 2
            }
            return delay.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
    }
}

/**
 * 撤回同意后的凭据读取闸（R1-01，BR-ID-13）：撤回时先记下「待清理」（内存 + [PrivacyStore.withdrawalCleanupPending] 落盘），
 * 清理全部成功才去掉。待清理期间：
 * - [readable]：每次调用先重试清理；仍没完成返回 false——调用方必须当作没有任何会话凭据（游客、未注册设备），不得读取；
 * - [PrivacyGate.coldStart] 启动时重试，[PrivacyGate.onConsented] 初始化前重试，没完成不初始化。
 * TODO(APP-05)/TODO(APP-03): 会话、设备注册实现后，读令牌、device_id、install_secret 的唯一入口先调 [readable]
 * （现在由 AppRouting 的会话状态源调用）。
 */
class CredentialAccessGuard internal constructor(
    private val store: PrivacyStore,
    private val cleanup: () -> Unit,
) {
    /** 本进程内的标记：存储不落盘（测试替身）时同一进程照样重试。 */
    private var pendingInMemory = false

    /** 撤回同意的本地清理还没完成。 */
    val cleanupPending: Boolean get() = pendingInMemory || store.withdrawalCleanupPending

    /** 会话凭据现在可以读：没有待清理，或重试清理成功。 */
    fun readable(): Boolean = !cleanupPending || runCleanup() == null

    /** 撤回开始：「待清理」+ 基本模式 + 清同意记录一次原子落盘（R2-01），之后任何一步失败或进程被杀都能在下次重试。 */
    internal fun beginWithdrawal() {
        pendingInMemory = true
        store.commitWithdrawal()
    }

    /** 执行一次清理；成功去掉标记并返回 null，失败返回错误（标记保留）。 */
    internal fun runCleanup(): Throwable? {
        val failure = runCatching(cleanup).exceptionOrNull()
        if (failure == null) {
            store.withdrawalCleanupPending = false
            pendingInMemory = false
        }
        return failure
    }
}

/**
 * 本地记录 → 决定。记录矛盾或损坏时取最保守的解释（rule-gaps 第 2 条：恢复策略未规定）：
 * basicMode = true 一律按基本模式；不认识的决定按未决定。TODO(N-14): 规则补恢复策略后对齐三端。
 */
private fun PrivacyStore.effectiveDecision(): PrivacyDecision =
    if (basicMode) PrivacyDecision.BASIC_MODE else PrivacyDecision.fromRaw(decisionRaw)

/** 冷启动时该弹哪个弹窗。缺版本或版本不是整数：按「低于包内版本」重新同意（保守）。 */
private fun PrivacyStore.coldStartStage(bundledVersion: Long): PromptStage =
    when (effectiveDecision()) {
        PrivacyDecision.NOT_DECIDED -> {
            PromptStage.FIRST
        }

        PrivacyDecision.BASIC_MODE -> {
            PromptStage.NONE
        }

        PrivacyDecision.CONSENTED -> {
            val consented = consentedVersion?.toLongOrNull()
            if (consented != null && consented >= bundledVersion) PromptStage.NONE else PromptStage.FIRST
        }
    }

/**
 * 冷启动（新进程）恢复中断的撤回（R2-01）：盘上有「待清理」标记就不论其他字段，重新提交撤回事务（基本模式 + 清同意记录，
 * 标记保持），之后才读决定。幂等：撤回已完整落盘时重写同样的值。撤回后在同一进程里又同意、清理却没完成就被杀，
 * 也按基本模式处理，用户需要重新同意（保守）。
 */
private fun PrivacyStore.recoverInterruptedWithdrawal(): PrivacyStore {
    if (withdrawalCleanupPending) commitWithdrawal()
    return this
}

/** 进入并保存基本模式（仍不同意）。 */
private fun PrivacyStore.persistBasicMode() {
    decisionRaw = PrivacyDecision.BASIC_MODE.raw
    basicMode = true
}

/** 按 [PrivacyGate.INIT_ORDER] 排序；不在其中的名字排在后面、按登记顺序。 */
private fun orderedInitSteps(steps: List<Pair<String, () -> Unit>>): List<Pair<String, () -> Unit>> {
    val known = PrivacyGate.INIT_ORDER.mapNotNull { name -> steps.firstOrNull { it.first == name } }
    return known + steps.filter { it.first !in PrivacyGate.INIT_ORDER }
}

/**
 * 生产用初始化步骤：第三方 SDK 的初始化调用只能写在这里（本文件），由 [PrivacyGate.onConsented] 按顺序调用。
 * 设备注册（APP-03b）由 App 传入；其余步骤是空实现，各自任务接入时填写。设备注册失败（回调 false）触发退避重试。
 */
fun PrivacyGate.registerProductionInitSteps(
    union: () -> Unit = {},
    push: () -> Unit = {},
    deviceRegistration: (onResult: (Boolean) -> Unit) -> Unit,
) {
    // POST /v1/devices 设备注册（APP-03b DeviceRegistry.registerAsync，后台执行，失败退避重试）。
    // TODO(APP-12): 注册成功后上报本地同意记录（client_at = consentedAtMillis，BR-ID-12）；TODO(APP-15): 推送 token。
    registerDeviceStep(deviceRegistration)
    // 推送令牌来源（APP-12 G17）：只启动 PushTokenSource（Release 为空操作，不初始化任何推送 SDK）。
    // TODO(N-33): MobPush（同意后调用其隐私授权接口，03 §3.5；AppKey 等由负责人提供，不写进源码字面量）。
    registerInitStep(PrivacyGate.PUSH) { push() }
    // 联盟 SDK（03 §3.5）：百川适配层（APP-06b；SDK 未引入、AppKey 未给，不可用实现初始化一律失败并上报 sdk_init_failed，TODO(N-28)）。
    // 京东 / 拼多多 SDK 不引入（任务书 APP-06b §2.4）。
    registerInitStep(PrivacyGate.UNION) { union() }
    // TODO(APP-16): Sentry Android（境内自建服务端，关闭默认个人信息上报）。
    registerInitStep(PrivacyGate.CRASH) {}
    // TODO(APP-05): 微信 OpenSDK。
    registerInitStep(PrivacyGate.WECHAT) {}
}
