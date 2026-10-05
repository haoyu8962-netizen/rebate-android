// lint 违反样例（feature-to-feature-dependency，03 §3.1）：feature:home 依赖 feature:search。
// tools/ci/verify.sh 以 -Pcouli.fixture.featureDependency=<本文件> 把它加到 :feature:home，构建必须失败。
dependencies {
    add("implementation", project(":feature:search"))
}
