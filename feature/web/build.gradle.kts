// feature:web —— 两类 WebView 容器（APP-04；03 §5.1、§5.2、§4.8 CSB-02/03/10）：TrustedWebView（自有 H5，注入随包的桥启动脚本，
// 传输层 WebViewCompat.addWebMessageListener）与 ExternalWebView（第三方页，不注入任何东西，导航经 ExternalNavPolicy）。
// WebView 的设置只写在这里的容器封装里。feature 之间不得互相依赖（couli.android.library 检查），跨功能跳转只经 Router。
import com.android.build.api.variant.HasDeviceTests
import javax.inject.Inject

plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.web"
}

/**
 * 仪器测试的本机回环 TLS 材料（LoopbackHttpsServer、测试 APK 的 network-security-config 用）：构建时用 JDK 自带的 keytool 现生成，
 * 仓库里不保存任何私钥或证书（AGENTS.md 硬规则 7）。产物只在 build/generated/loopbackTls/ 下（build/ 已被 .gitignore 忽略）：
 *   assets/tls/loopback-test-only-server.p12  服务器私钥与证书链（PKCS12，口令固定 test-only），证书由下面的测试 CA 签发，
 *                                             SAN 为 IP:127.0.0.1、DNS:localhost；
 *   res/raw/loopback_test_only_ca.pem         测试 CA 的证书（PEM），network-security-config 以 @raw/loopback_test_only_ca 引用。
 * 只挂在 androidTest 组件的 assets、res 上（经 Variant API 的 addGeneratedSourceDirectory，合并任务自动依赖本任务），
 * 主源集与 Debug / Release 应用都不包含这些文件。
 */
abstract class GenerateLoopbackTlsTask : DefaultTask() {
    @get:Input
    abstract val keytool: Property<String>

    @get:Input
    abstract val storePassword: Property<String>

    @get:Input
    abstract val subjectAltName: Property<String>

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val work =
            temporaryDir.apply {
                deleteRecursively()
                mkdirs()
            }
        val caStore = File(work, "ca.p12")
        val serverStore = File(work, "server.p12")
        val csr = File(work, "server.csr")
        val serverCert = File(work, "server.pem")
        val caPem = File(work, "ca.pem")
        val pass = storePassword.get()

        fun keytool(vararg args: String) {
            execOps.exec { commandLine(listOf(keytool.get()) + args + listOf("-storetype", "PKCS12", "-storepass", pass)) }
        }

        // 测试 CA（自签，basicConstraints CA:true）；起始时间提前一天，免得宿主与模拟器的时钟差导致「尚未生效」
        keytool(
            "-genkeypair",
            "-alias",
            "ca",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
            "-startdate",
            "-1d",
            "-dname",
            "CN=couli loopback test-only CA",
            "-ext",
            "bc:c",
            "-ext",
            "KU:c=keyCertSign,cRLSign",
            "-keystore",
            caStore.path,
        )
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", caStore.path, "-file", caPem.path)
        // 服务器密钥对 → 证书请求 → 由测试 CA 签发（SAN：127.0.0.1、localhost）
        keytool(
            "-genkeypair",
            "-alias",
            "server",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
            "-dname",
            "CN=localhost",
            "-keystore",
            serverStore.path,
        )
        keytool("-certreq", "-alias", "server", "-keystore", serverStore.path, "-file", csr.path)
        keytool(
            "-gencert",
            "-rfc",
            "-alias",
            "ca",
            "-keystore",
            caStore.path,
            "-infile",
            csr.path,
            "-outfile",
            serverCert.path,
            "-validity",
            "3650",
            "-startdate",
            "-1d",
            "-ext",
            "SAN=${subjectAltName.get()}",
            "-ext",
            "KU:c=digitalSignature,keyEncipherment",
            "-ext",
            "EKU=serverAuth",
        )
        // 先导入 CA 作信任条目，再导入签发后的证书作为 server 条目的应答，keystore 里即是完整的 server → CA 链
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caPem.path, "-keystore", serverStore.path)
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", serverCert.path, "-keystore", serverStore.path)

        val assets = assetsDir.get().asFile.apply { deleteRecursively() }
        val res = resDir.get().asFile.apply { deleteRecursively() }
        serverStore.copyTo(File(assets, "tls/loopback-test-only-server.p12"))
        caPem.copyTo(File(res, "raw/loopback_test_only_ca.pem"))
        work.deleteRecursively()
    }
}

val generateLoopbackTls =
    tasks.register<GenerateLoopbackTlsTask>("generateLoopbackTls") {
        group = "verification"
        description = "仪器测试用的本机回环测试 CA 与服务器 PKCS12（keytool 现生成，不入库）"
        val javaHome = providers.environmentVariable("JAVA_HOME").orElse(providers.systemProperty("java.home"))
        keytool.set(javaHome.map { File(it, "bin/keytool").path })
        storePassword.set("test-only")
        subjectAltName.set("IP:127.0.0.1,DNS:localhost")
        assetsDir.set(layout.buildDirectory.dir("generated/loopbackTls/assets"))
        resDir.set(layout.buildDirectory.dir("generated/loopbackTls/res"))
    }

androidComponents {
    onVariants { variant ->
        (variant as? HasDeviceTests)?.deviceTests?.values?.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(generateLoopbackTls, GenerateLoopbackTlsTask::assetsDir)
            deviceTest.sources.res?.addGeneratedSourceDirectory(generateLoopbackTls, GenerateLoopbackTlsTask::resDir)
        }
    }
}

dependencies {
    // BridgeProtocol、BridgeHost、ExternalNavPolicy；ClientEvents、DefaultTexts（core:generated，经 core:bridge 的 api）
    api(project(":core:bridge"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 容器实例（WebView、控制器）跟随路由的回退栈条目保留：被盖住不重建，出栈时拆除（R1-04、共性第 28 条）
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 容器封装的源码约束（CSB-10：ExternalWebView 不出现注入调用）
    testImplementation(libs.junit4)

    // 真实 WebView 的仪器测试：包内测试 HTML（子框架、非可信 origin、缺手势、第三方页无桥、平台域导航被拦）
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // ExternalWebScreen 的「去搜索」提示（APP-04 三端共性第 21 条）；版本由 Compose BOM 管
    androidTestImplementation(libs.compose.ui.test.junit4)
    // 真实 NavHost 清栈时容器同步拆除（R2-03）；生产代码不依赖 Navigation，由 App 把回退栈交给 WebContainers
    androidTestImplementation(libs.androidx.navigation.compose)
}
