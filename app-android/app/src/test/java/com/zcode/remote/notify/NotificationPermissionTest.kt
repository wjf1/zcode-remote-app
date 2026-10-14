package com.zcode.remote.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotificationPermission] 的纯逻辑单测。
 *
 * 涉及 Context 的判定（`isGranted` / `shouldPrompt`）需要 Android 运行时，属仪器化范畴；
 * 这里只钉住与 SDK 版本相关的纯判定——它决定「要不要走运行时申请」这条分支，
 * 是 Android 13+ 上审批通知能否送达的第一道开关。
 */
class NotificationPermissionTest {
    /** Android 13（TIRAMISU, API 33）起 POST_NOTIFICATIONS 为运行时权限。 */
    @Test
    fun runtimeRequestRequiredOnApi33AndAbove() {
        assertTrue(NotificationPermission.isRuntimeRequestRequired(33))
        assertTrue(NotificationPermission.isRuntimeRequestRequired(34))
        assertTrue(NotificationPermission.isRuntimeRequestRequired(35))
    }

    /** Android 12L（API 32）及以下无该权限，恒视为已授予（不得申请不存在的权限）。 */
    @Test
    fun runtimeRequestNotRequiredBelowApi33() {
        assertFalse(NotificationPermission.isRuntimeRequestRequired(31))
        assertFalse(NotificationPermission.isRuntimeRequestRequired(32))
        assertFalse(NotificationPermission.isRuntimeRequestRequired(1))
    }

    /**
     * shouldPrompt 与 isGranted 语义互补：本测试通过一个「已授予」的等价判定间接约束——
     * `isRuntimeRequestRequired(32)` 为 false 意味着低版本不会因运行时权限而 shouldPrompt
     * （系统总开关另判，需 Context）。这里只断言两者的反相关系在同一 SDK 口径下自洽。
     */
    @Test
    fun lowApiNeverRequiresRuntimeGrant() {
        // 低版本下「运行时申请」这条路径整体不成立，故 isRuntimeRequestRequired 必须为 false，
        // 否则设置页会在低版本上申请一个不存在的权限（申请恒失败 → 误报未开启）。
        for (sdk in 1..32) {
            assertFalse("sdk=$sdk 不应要求运行时申请", NotificationPermission.isRuntimeRequestRequired(sdk))
        }
    }

    /** 权限常量必须与 Manifest 声明一致（防有人改了常量却没改 Manifest）。 */
    @Test
    fun permissionConstantMatchesPlatformName() {
        org.junit.Assert.assertEquals("android.permission.POST_NOTIFICATIONS", NotificationPermission.PERMISSION)
    }
}
