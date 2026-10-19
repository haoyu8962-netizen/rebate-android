package com.couli.core.clipboard

/**
 * 候选提取（FU-AND-0724；BR-ID-16 细则「本地过滤与去重默认值」「候选提取、上传与弹卡」；R21 / R23）：自动任务与手动 product_lookup 共用。
 * - 本地过滤：长度 10–500 码点；≤20 位的纯数字或纯字母串排除（G26：数字字母混合不算）；排除词（配置下发，缺省空）；
 * - 候选：白名单链接（去掉 [shareDomains] 下的邀请落地页 `/i/{code}`）与淘口令，按原文位置排序、最多 3 个（复用 [ClipboardCandidates]）；
 *   按平台关闭（features.flags 的 clipboard.enabled.<平台>=false）由调用方先收紧 [rules]，只过滤、不停读取；
 * - 结果：命中 → product，[ClipboardClassification.candidates] 为全部候选、[ClipboardClassification.uploadText] = 候选 + 最小化后的「」标题；
 *   未命中 → none，候选为空、原因见 [ClipboardClassification.reasonRaw]。结果不含原文，也不含原文的 hash（去重摘要由调用方按候选算）。
 * 自写排除、去重、邀请分支不在这里（只对自动任务 / 另有独立分支）。
 */
object CandidateExtractor {
    fun extract(
        text: String,
        rules: ClipboardRules = ClipboardRules.defaults,
        shareDomains: List<String> = emptyList(),
    ): ClipboardClassification {
        val candidates = ClipboardCandidates.extract(text, rules, shareDomains)
        val rejection = ClipboardClassifier.productRejection(text, candidates.map { it.raw }, rules)
        if (rejection != null) return ClipboardClassification(kindRaw = ClipboardClassifier.KIND_NONE, reasonRaw = rejection)
        return ClipboardClassification(
            kindRaw = ClipboardClassifier.KIND_PRODUCT,
            uploadText = ClipboardCandidates.uploadOf(candidates, text, rules, shareDomains),
            candidates = candidates,
        )
    }

    /**
     * 自动任务的去重摘要输入（G07）：候选按提取顺序去重后用换行连接，不含标题与原文包装；调用方再用本机持久盐 HMAC（[ClipboardFingerprint]）。
     */
    fun summaryInput(candidates: List<ClipboardCandidate>): String = candidates.map { it.raw }.distinct().joinToString("\n")
}
