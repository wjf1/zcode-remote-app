package com.zcode.remote.ui.screens

/**
 * 会话消息流的自动定位/贴底判定（体验提升任务书 v2 · B-1）。
 *
 * 抽成纯函数的理由：本项目没有 `src/androidTest`（真机验收只能人工做），
 * 能被 CI 守住的只有 JVM 纯函数测试，故判定逻辑必须与 Compose 解耦。
 *
 * ## 模型：意图锁，而不是「离底部多远」
 *
 * 早期实现用「距离末尾 <= 2 项」判断要不要贴底，真机实测暴露了它的缺陷：
 * 用户点「回到底部」后，若滚动动画进行中又有新行到达，动画目标索引立刻过期，
 * 视口停在半路、`nearBottom` 为假，于是**后续增量全部不跟随**，卡在中间。
 * 根因是用「几何距离」去表达「用户是否想待在底部」这个**意图**。
 *
 * 现在改用意图锁 [userScrolledAway]：只有用户**主动拖动**才置位（程序化滚动不会），
 * 用户拖回底部或点「回到底部」即复位。置位期间一切自动定位让位。
 */
object ConversationScrollPolicy {

    /** 触发自动定位的来源。 */
    enum class Trigger {
        /** 进会话首帧——覆盖「有缓存」「无缓存」两种时序。 */
        Enter,

        /** 权威快照 replaceAll 落地（已对齐）——覆盖「缓存行数 == 快照行数」这条 rows.size 不变的新失效路径。 */
        SnapshotAligned,

        /** 流式增量（末行内容变化）。 */
        Delta,
    }

    /**
     * 是否应把视口定位到最新一行。
     *
     * @param rowCount 当前行数
     * @param paginationInFlight 正在加载更早历史（`earlier.loading`）或正在恢复翻页锚点（`anchorRowId != null`）
     * @param alreadyPinned 本会话是否已执行过「进会话定位」（由调用方按会话重置）
     * @param userScrolledAway 用户已主动上翻离开底部（意图锁；拖回底部或点「回到底部」后复位）
     */
    fun shouldPinToLatest(
        trigger: Trigger,
        rowCount: Int,
        paginationInFlight: Boolean,
        alreadyPinned: Boolean,
        userScrolledAway: Boolean,
    ): Boolean {
        if (rowCount <= 0) return false
        // 翻页 / 锚点恢复进行中：任何触发都不得强制定位，
        // 否则刚加载进来的更早历史会被立刻拉回底部（把翻页体验改坏）。
        if (paginationInFlight) return false
        // 用户主动上翻查阅历史：尊重其位置，只有显式点「回到底部」才回去。
        if (userScrolledAway) return false
        return when (trigger) {
            // 进会话必须定位一次（有缓存 / 无缓存 / 缓存行数==快照行数 三种时序都靠它兜底）
            Trigger.Enter -> !alreadyPinned
            // 快照对齐是权威内容落地，必定位
            Trigger.SnapshotAligned -> true
            // 增量持续跟随：意图锁未置位即视为「用户想待在底部」。
            // 这里**不能**再加「离底部很近」的几何条件——那正是上面的竞态根因。
            Trigger.Delta -> alreadyPinned
        }
    }
}
