package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0715 第 1 项（F-AND-32 + 2026-10-15 补充；对照鸿蒙 FU-HM-0715 R1-01..04）。全部用真实 [KeystoreSessionTokens] + [EncryptedPreferences]
 * （可逆假加密、按提交可控的 SharedPreferences），独立介质的撤销标记用可控的 [RevocationMarks]（文件实现另有一例）。
 * - 令牌文件整体不可写（同文件撤销标记与令牌删除都提交失败）：独立介质的标记兜住，未重新登录即重建不交出旧会话、世代 null；两种介质都写不进时只剩本进程
 *   隔离（残余，单独一例断言现状）。
 * - 持久化会话完整校验：空令牌、非法作用域、类型不对、整组不齐 → 整组隔离（不加载、世代 null），删除失败时重建也读不回。
 * - 撤销标记在删除令牌之前落盘；独立介质标记带完整性校验（空、截断、摘要不符一律按撤销）；完整的旧标记不误伤新登录。
 * - 清会话路径不重读受禁凭据：撤回待清理、环境未就绪、冷启动清理，真实令牌存储 + 计数解密为 0。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0715SessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 可逆的假加密；计数解密（= 读取令牌明文）。 */
    private class CountingCipher : SecretCipher {
        val decrypts = AtomicInteger()

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            decrypts.incrementAndGet()
            return sealed.map { it.toInt().inv().toByte() }.toByteArray()
        }
    }

    /** 提交可控：[writable] 为 false 时每次提交都失败（盘上原样，令牌文件整体写不进）。提交成功 / 失败都记进 [log]。 */
    private class ControlledPreferences(
        val inner: SharedPreferences,
        private val log: MutableList<String>,
    ) : SharedPreferences by inner {
        @Volatile var writable = true

        override fun edit(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, String?>()
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(
                    key: String,
                    value: String?,
                ): SharedPreferences.Editor = apply { changes[key] = value }

                override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }

                override fun commit(): Boolean {
                    val what = if (changes.values.all { it == null }) "delete" else "write"
                    if (!writable) {
                        log += "prefs-$what-failed:${changes.keys.sorted().joinToString(",")}"
                        return false
                    }
                    val out = inner.edit()
                    changes.forEach { (k, v) -> if (v == null) out.remove(k) else out.putString(k, v) }
                    log += "prefs-$what:${changes.keys.sorted().joinToString(",")}"
                    return out.commit()
                }

                override fun apply() {
                    commit()
                }
            }
        }
    }

    /** 可控的独立介质：先写临时文件再「改名」成正式文件；各步可失败；[onStep] 在每一步落盘后回调（取中断点快照）。 */
    private class FakeMarks(
        private val log: MutableList<String>,
    ) : RevocationMarks {
        var formal: String? = null
        var temp: String? = null
        var writeFails = false
        var renameFails = false
        var deleteFails = false
        var readFails = false
        var onStep: (String) -> Unit = {}

        override fun read(): List<String> {
            if (readFails) throw IOException("marks unreadable")
            return listOfNotNull(formal, temp)
        }

        override fun write(text: String) {
            if (writeFails) throw IOException("marks unwritable")
            temp = text
            log += "mark-temp"
            onStep("temp")
            if (renameFails) throw IOException("rename failed")
            formal = text
            temp = null
            log += "mark"
            onStep("formal")
        }

        override fun delete() {
            if (deleteFails) throw IOException("cannot delete")
            formal = null
            temp = null
        }

        fun copy(): FakeMarks =
            FakeMarks(mutableListOf()).also {
                it.formal = formal
                it.temp = temp
            }
    }

    private var serial = 0

    private inner class Rig(
        name: String,
    ) {
        val log = mutableListOf<String>()
        val raw: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val prefs = ControlledPreferences(raw, log)
        val marks = FakeMarks(log)
        val cipher = CountingCipher()

        fun tokens(
            on: SharedPreferences = prefs,
            with: RevocationMarks? = marks,
            cipher: SecretCipher = this.cipher,
        ) = KeystoreSessionTokens(EncryptedPreferences(context, cipher, backing = on), with) { "epoch-${++serial}" }

        /** 此刻的「盘」（令牌文件 + 独立介质）复制一份，模拟在这一刻进程被杀后重建。 */
        fun snapshot(tag: String): KeystoreSessionTokens {
            val copy = context.getSharedPreferences("$tag-${++serial}", Context.MODE_PRIVATE)
            copy
                .edit()
                .clear()
                .apply { raw.all.forEach { (k, v) -> putString(k, v as String) } }
                .commit()
            return tokens(on = copy, with = marks.copy())
        }
    }

    private fun KeystoreSessionTokens.login(account: String = "user-a") =
        save("access-$account", "refresh-$account", SessionScopeRaw.FULL, account)

    // ---------------------------------------------------------------- F-AND-32：令牌文件整体不可写

    @Test
    fun fand32_whole_token_file_unwritable_independent_mark_revokes_across_rebuild_until_a_new_login() {
        val rig = Rig("fuand0715-whole-unwritable")
        val store = rig.tokens()
        store.login()
        val epoch = store.persistedLoginEpoch()
        assertNotNull(epoch)

        // 退出：同文件撤销标记与令牌删除都提交失败
        rig.prefs.writable = false
        rig.log.clear()
        assertTrue(runCatching { store.clear() }.isFailure)
        // 撤销标记先于令牌删除：同文件标记提交失败 → 独立介质标记落盘（临时文件 → 正式文件）→ 才提交令牌删除
        val localMarkAt = rig.log.indexOf("prefs-write-failed:session_revoked")
        val markAt = rig.log.indexOf("mark")
        val deleteAt = rig.log.indexOfFirst { it.startsWith("prefs-delete-failed:") && KeystoreSessionTokens.KEY_ACCESS in it }
        assertTrue("顺序：${rig.log}", localMarkAt in 0 until markAt && markAt < deleteAt)
        assertEquals(SessionRevocation.encode("e:$epoch"), rig.marks.formal)
        assertNotNull("前提：旧令牌仍在盘上", rig.raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNull("前提：同文件标记没写进", rig.raw.getString(KeystoreSessionTokens.KEY_REVOKED, null))

        // 未重新登录即重建（仍写不进）：不交出旧会话，世代 null
        val rebuilt = rig.tokens()
        assertNull("重建后旧会话不交出", rebuilt.accessToken)
        assertNull("重建后世代为 null（四类缓存不读）", rebuilt.persistedLoginEpoch())
        assertNull("再重建一次也一样", rig.tokens().persistedLoginEpoch())

        // 存储恢复：重建的存储读一次就清干净（令牌 + 两处标记）；新登录换新世代，可用
        rig.prefs.writable = true
        assertNull(rig.tokens().accessToken)
        assertNull("撤销完成：盘上没有旧令牌", rig.raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertNull("独立介质标记随之删除", rig.marks.formal)
        val again = rig.tokens()
        again.login()
        val renewed = rig.tokens().persistedLoginEpoch()
        assertNotNull(renewed)
        assertNotEquals(epoch, renewed)
    }

    @Test
    fun fand32_every_interrupt_point_between_mark_and_token_delete_reads_nothing_back() {
        for (renameFails in listOf(false, true)) {
            val rig = Rig("fuand0715-interrupt-$renameFails")
            val store = rig.tokens()
            store.login()
            val rebuilt = mutableMapOf<String, KeystoreSessionTokens>()
            rig.marks.renameFails = renameFails
            rig.marks.onStep = { step -> rebuilt[step] = rig.snapshot("fuand0715-cut-$step") }
            rig.prefs.writable = false
            runCatching { store.clear() }
            assertTrue("renameFails=$renameFails：至少截到临时文件这一步", "temp" in rebuilt)
            rebuilt.forEach { (step, tokens) ->
                assertNull("renameFails=$renameFails 截在 $step：重建不交出旧会话", tokens.accessToken)
                assertNull("renameFails=$renameFails 截在 $step：世代 null", tokens.persistedLoginEpoch())
            }
            // 令牌删除入口那一刻（标记都已落盘）重建
            val atDelete = rig.snapshot("fuand0715-cut-delete")
            assertNull("renameFails=$renameFails 截在删除入口", atDelete.persistedLoginEpoch())
        }
    }

    @Test
    fun fand32_residual_both_media_unwritable_leaves_only_in_process_isolation() {
        val rig = Rig("fuand0715-residual")
        val store = rig.tokens()
        store.login()
        rig.prefs.writable = false
        rig.marks.writeFails = true
        assertTrue(runCatching { store.clear() }.isFailure)
        assertNull("本进程：世代禁用", store.persistedLoginEpoch())
        // 残余（见 rule-gaps / handback 疑点）：两种介质都写不进时没有介质能落下撤销，重建后旧会话回来
        val rebuilt = rig.tokens()
        assertEquals("access-user-a", rebuilt.accessToken)
        assertNotNull(rebuilt.persistedLoginEpoch())
    }

    @Test
    fun fand32_real_file_marks_survive_rebuild_and_are_deleted_after_new_login() {
        val rig = Rig("fuand0715-file")
        val file = File(context.noBackupFilesDir, "fuand0715-${KeystoreSessionTokens.REVOCATION_FILE}").apply { delete() }
        val marks = FileRevocationMarks(file)
        val store = rig.tokens(with = marks)
        store.login()
        rig.prefs.writable = false
        runCatching { store.clear() }
        assertTrue("标记文件已落盘", file.exists())
        assertNull(rig.tokens(with = FileRevocationMarks(file)).persistedLoginEpoch())
        rig.prefs.writable = true
        val fresh = rig.tokens(with = FileRevocationMarks(file))
        fresh.login()
        assertNotNull(fresh.persistedLoginEpoch())
        assertTrue("新登录落盘后标记文件删除", !file.exists() && !File(file.parentFile, file.name + ".tmp").exists())
        assertNotNull(rig.tokens(with = FileRevocationMarks(file)).persistedLoginEpoch())
    }

    // ---------------------------------------------------------------- 补充：完整性校验、新登录不误伤

    @Test
    @Suppress("NestedBlockDepth") // 每种不完整标记 × 正式 / 临时文件
    fun fand32_incomplete_marks_block_any_session_and_complete_old_mark_spares_new_login() {
        val rig = Rig("fuand0715-integrity")
        val store = rig.tokens()
        store.login()
        val epoch = checkNotNull(store.persistedLoginEpoch())
        val complete = SessionRevocation.encode("e:$epoch")
        val broken =
            listOf("", complete.dropLast(4), complete.substringBeforeLast("|"), complete.replace("|end", "|xxx"), "garbage", "e:$epoch")
        for (text in broken) {
            for (inTemp in listOf(false, true)) {
                val marks = FakeMarks(mutableListOf()).apply { if (inTemp) temp = text else formal = text }
                val rebuilt = rig.tokens(with = marks)
                assertNull("不完整标记「$text」（临时=$inTemp）：按撤销", rebuilt.accessToken)
                // 被阻断后会删令牌：恢复会话再试下一种
                rig.tokens(with = null).login()
            }
        }
        // 摘要对不上（目标被改）
        val tampered = SessionRevocation.encode("e:other").replace("e:other", "e:$epoch".take(7))
        assertNull(rig.tokens(with = FakeMarks(mutableListOf()).apply { formal = tampered }).accessToken)
        // 完整的旧标记（目标是别的世代）：不误伤
        val current = rig.tokens(with = null)
        current.login()
        val old = FakeMarks(mutableListOf()).apply { formal = SessionRevocation.encode("e:epoch-old") }
        assertEquals("完整的旧标记不阻断新登录", "access-user-a", rig.tokens(with = old).accessToken)
        assertNotNull(rig.tokens(with = old).persistedLoginEpoch())
        // 独立介质读不出：按会话读不出（不交出旧令牌、不删除）
        val unreadable = FakeMarks(mutableListOf()).apply { readFails = true }
        assertTrue(rig.tokens(with = unreadable).readSession() is SessionRead.Unreadable)
        assertNull(rig.tokens(with = unreadable).persistedLoginEpoch())
        assertNotNull("读不出不删除", rig.raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
    }

    @Test
    fun fand32_any_session_mark_left_after_failed_delete_is_superseded_by_new_login() {
        val rig = Rig("fuand0715-supersede")
        // 没装载过就清除（冷启动清理）：不知道是哪份会话 → 「任何会话」标记
        rig.tokens(with = null).login()
        val cold = rig.tokens()
        rig.prefs.writable = false
        runCatching { cold.clear() }
        assertEquals(SessionRevocation.encode(SessionRevocation.ANY), rig.marks.formal)
        rig.prefs.writable = true
        // 新登录：独立介质标记删不掉 → 改写成「除了这次登录」
        rig.marks.deleteFails = true
        val fresh = rig.tokens()
        fresh.login("user-b")
        val renewed = checkNotNull(fresh.persistedLoginEpoch())
        assertEquals(SessionRevocation.encode(SessionRevocation.supersededBy(renewed)), rig.marks.formal)
        assertEquals("重建后新登录照常可用", "access-user-b", rig.tokens().accessToken)
        // 把一份别的会话放回令牌项（旧登录 / 升级前没有世代）：仍被阻断
        rig.tokens(with = null).login("user-c")
        assertNull("别的登录世代仍被阻断", rig.tokens().accessToken)
    }

    // ---------------------------------------------------------------- 补充：持久化会话完整校验

    @Test
    fun fand32_invalid_persisted_session_fields_isolate_the_whole_session_even_when_delete_fails() {
        val cases =
            linkedMapOf<String, (SharedPreferences.Editor, (String) -> String) -> Unit>(
                "空 access_token" to { e, enc -> e.putString(KeystoreSessionTokens.KEY_ACCESS, enc("")) },
                "空 refresh_token" to { e, enc -> e.putString(KeystoreSessionTokens.KEY_REFRESH, enc("")) },
                "非法作用域" to { e, enc -> e.putString(KeystoreSessionTokens.KEY_SCOPE, enc("BROKEN")) },
                "空账号" to { e, enc -> e.putString(KeystoreSessionTokens.KEY_USER_ID, enc("")) },
                "空世代" to { e, enc -> e.putString(KeystoreSessionTokens.KEY_LOGIN_EPOCH, enc("")) },
                "缺 refresh_token" to { e, _ -> e.remove(KeystoreSessionTokens.KEY_REFRESH) },
                "缺作用域" to { e, _ -> e.remove(KeystoreSessionTokens.KEY_SCOPE) },
                "作用域类型错（整数）" to { e, _ -> e.putInt(KeystoreSessionTokens.KEY_SCOPE, 1) },
                "令牌类型错（布尔）" to { e, _ -> e.putBoolean(KeystoreSessionTokens.KEY_ACCESS, true) },
            )
        val cipher = CountingCipher()

        fun enc(text: String) = android.util.Base64.encodeToString(cipher.encrypt(text.toByteArray()), android.util.Base64.NO_WRAP)
        for ((name, damage) in cases) {
            val rig = Rig("fuand0715-invalid-${name.hashCode()}")
            val store = rig.tokens()
            store.login()
            assertNotNull(store.persistedLoginEpoch())
            rig.raw
                .edit()
                .also { damage(it, ::enc) }
                .commit()
            rig.prefs.writable = false
            assertNull("$name：整组不加载", store.accessToken)
            assertNull("$name：世代 null", store.persistedLoginEpoch())
            assertNull("$name：删除失败也保持隔离", store.persistedLoginEpoch())
            val rebuilt = rig.tokens()
            assertNull("$name：重建（删除仍失败）读不回", rebuilt.accessToken)
            assertNull("$name：重建世代 null", rebuilt.persistedLoginEpoch())
            rig.prefs.writable = true
            assertNull("$name：存储恢复后被删掉", rig.tokens().accessToken)
        }
    }

    @Test
    fun fand32_valid_sessions_still_load_including_pre_upgrade_without_epoch() {
        val rig = Rig("fuand0715-valid")
        val store = rig.tokens()
        store.save("access-1", "refresh-1", SessionScopeRaw.DELETION_ONLY, null)
        assertEquals("deletion_only、没有账号：照常可用", SessionScopeRaw.DELETION_ONLY, store.scope)
        assertNotNull(store.persistedLoginEpoch())
        rig.raw
            .edit()
            .remove(KeystoreSessionTokens.KEY_LOGIN_EPOCH)
            .commit()
        assertEquals("升级前登录（没有世代）：令牌照常可用", "access-1", rig.tokens().accessToken)
        assertNull("但世代未知", rig.tokens().persistedLoginEpoch())
    }

    // ---------------------------------------------------------------- 补充：清会话路径零读取

    private fun client(
        tokens: SessionTokens,
        settled: () -> Boolean,
        readable: () -> Boolean,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { "https://api.example.test/" },
        context = { NetworkContext(PrivacyRaw.FULL, settled(), readable(), UpdateRaw.SUPPORTED) },
        device =
            object : DeviceCredentials {
                override val deviceId: String = "device-fixture"
                override val installSecret: String = "test-only.install.device-a"
            },
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        sleeper = {},
    )

    @Test
    fun fand32_clearing_paths_never_reread_forbidden_credentials_with_real_tokens() {
        for (path in listOf("withdrawal", "environment_unsettled", "cold_start_cleaner")) {
            val rig = Rig("fuand0715-zero-$path")
            val setup = rig.tokens()
            setup.login()
            val epoch = checkNotNull(setup.persistedLoginEpoch())
            val store = if (path == "cold_start_cleaner") rig.tokens() else setup
            var settled = true
            var readable = true
            val api = client(store, { settled }, { readable })
            if (path != "cold_start_cleaner") assertEquals("user-a", api.activeUserId())
            // 状态已发布（撤回待清理 / 环境未就绪），令牌文件写不进
            when (path) {
                "withdrawal" -> readable = false
                "environment_unsettled" -> settled = false
            }
            rig.prefs.writable = false
            rig.cipher.decrypts.set(0)
            if (path == "cold_start_cleaner") {
                runCatching { store.clear() }
            } else {
                api.clearSessionIf(store.loginEpoch)
                // 清理器（AppModule 登记的 tokens.clear）随后运行
                runCatching { store.clear() }
            }
            assertEquals("$path：清会话路径零解密", 0, rig.cipher.decrypts.get())
            val target = if (path == "cold_start_cleaner") SessionRevocation.ANY else "e:$epoch"
            assertEquals("$path：撤销的是哪份会话取自记下的世代（不读令牌）", SessionRevocation.encode(target), rig.marks.formal)
            assertNull("$path：重建读不回", rig.tokens().accessToken)
        }
    }
}
