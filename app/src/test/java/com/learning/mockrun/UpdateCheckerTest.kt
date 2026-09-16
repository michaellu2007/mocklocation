package com.learning.mockrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isNewer 逐段比较,容忍 v 前缀与非数字尾巴`() {
        assertTrue(UpdateChecker.isNewer("0.0.4", "0.0.3"))
        assertTrue(UpdateChecker.isNewer("v0.1.0", "0.0.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("0.0.3", "0.0.3"))
        // 10 > 2 必须按数字比,不能按字符串比
        assertFalse(UpdateChecker.isNewer("0.0.2", "0.0.10"))
        assertFalse(UpdateChecker.isNewer("0.0.3-beta", "0.0.3"))
    }

    @Test
    fun `apkSources 镜像优先,原链兜底,容忍镜像尾斜杠`() {
        assertEquals(
            listOf(
                "https://gh.ddlc.top/https://github.com/a/b/releases/download/v1/x.apk",
                "https://gh-proxy.com/https://github.com/a/b/releases/download/v1/x.apk",
                "https://github.com/a/b/releases/download/v1/x.apk",
            ),
            UpdateChecker.apkSources(
                "https://github.com/a/b/releases/download/v1/x.apk",
                listOf("https://gh.ddlc.top/", "https://gh-proxy.com"),
            ),
        )
    }

    @Test
    fun `apkSources 无资产返回空,无镜像退化为原链`() {
        assertEquals(emptyList<String>(), UpdateChecker.apkSources(""))
        assertEquals(
            listOf("https://github.com/a/b/x.apk"),
            UpdateChecker.apkSources("https://github.com/a/b/x.apk", emptyList()),
        )
    }
}
