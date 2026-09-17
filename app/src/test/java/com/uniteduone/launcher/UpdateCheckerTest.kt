package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class UpdateCheckerTest {

    private val hex = "0123456789abcdef".repeat(4)
    private val https = "https://example.com/unitedu-1.0.1.apk"

    /** 一份合法的 latest.json(spec §7.2);各测试只替换其中一个字段。 */
    private fun latest(
        versionCode: String = "3",
        versionName: String = "\"1.0.1\"",
        notes: String? = "\"修复若干问题\"",
        apkUrl: String = "\"$https\"",
        sha256: String? = "\"$hex\"",
        minSdk: String? = "28",
    ): String = buildList {
        add("\"versionCode\": $versionCode")
        add("\"versionName\": $versionName")
        if (notes != null) add("\"notes\": $notes")
        add("\"apkUrl\": $apkUrl")
        if (sha256 != null) add("\"sha256\": $sha256")
        if (minSdk != null) add("\"minSdk\": $minSdk")
    }.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}\n")

    // ---- parseLatest ----

    @Test fun parsesAllFields() {
        val info = parseLatest(latest(sha256 = "\"${hex.uppercase()}\""))
        assertEquals(LatestInfo(3, "1.0.1", "修复若干问题", https, hex, 28), info)
    }

    @Test fun missingSha256IsRejected() {
        assertNull(parseLatest(latest(sha256 = null)))
    }

    @Test fun sha256MustBeExactly64Hex() {
        assertNull(parseLatest(latest(sha256 = "\"${hex.dropLast(1)}\"")))
        assertNull(parseLatest(latest(sha256 = "\"${hex}0\"")))
        assertNull(parseLatest(latest(sha256 = "\"${hex.dropLast(1)}g\"")))
        assertNull(parseLatest(latest(sha256 = "\"\"")))
    }

    @Test fun nonHttpsApkUrlIsRejected() {
        assertNull(parseLatest(latest(apkUrl = "\"http://example.com/unitedu.apk\"")))
        assertNull(parseLatest(latest(apkUrl = "\"ftp://example.com/unitedu.apk\"")))
        assertNull(parseLatest(latest(apkUrl = "\"http://127.0.0.1.evil.com/unitedu.apk\"")))
        assertNull(parseLatest(latest(apkUrl = "\"\"")))
    }

    @Test fun loopbackHttpApkUrlIsAcceptedForTheEmulatorTest() {
        val info = parseLatest(latest(apkUrl = "\"http://127.0.0.1:8123/unitedu.apk\""))
        assertEquals("http://127.0.0.1:8123/unitedu.apk", info?.apkUrl)
    }

    @Test fun versionFieldsAreRequired() {
        assertNull(parseLatest(latest().replace("\"versionCode\": 3,", "")))
        assertNull(parseLatest(latest(versionCode = "0")))
        assertNull(parseLatest(latest(versionCode = "-2")))
        assertNull(parseLatest(latest(versionCode = "\"3\"")))
        assertNull(parseLatest(latest(versionCode = "3.5")))
        assertNull(parseLatest(latest().replace("\"versionName\": \"1.0.1\",", "")))
        assertNull(parseLatest(latest(versionName = "\"  \"")))
    }

    @Test fun notesAndMinSdkAreOptional() {
        val info = parseLatest(latest(notes = null, minSdk = null))
        assertNotNull(info)
        assertEquals("", info!!.notes)
        assertEquals(1, info.minSdk)
    }

    @Test fun malformedMinSdkRejectsTheFile() {
        assertNull(parseLatest(latest(minSdk = "\"28\"")))
        assertNull(parseLatest(latest(minSdk = "true")))
    }

    @Test fun notJsonIsRejected() {
        assertNull(parseLatest(""))
        assertNull(parseLatest("<html><body>portal</body></html>"))
        // 截断在半路(连接中途断开时拿到的就是这种正文)
        assertNull(parseLatest(latest().substringBefore("\"sha256\"")))
        // 两段合法对象首尾拼接(追加式损坏)
        assertNull(parseLatest(latest() + latest()))
    }

    @Test fun jsonStringEscapesAreDecoded() {
        val notes = "\"第一行\\n第二行 \\\"引号\\\" \\\\ \\u4e2d\\u6587 a\\/b\""
        val info = parseLatest(latest(notes = notes))
        assertEquals("第一行\n第二行 \"引号\" \\ 中文 a/b", info?.notes)
    }

    @Test fun badEscapeInARequiredFieldRejectsTheFile() {
        assertNull(parseLatest(latest(versionName = "\"1.0\\q\"")))
        assertNull(parseLatest(latest(versionName = "\"1.0\\u12\"")))
        assertNull(parseLatest(latest(versionName = "\"1.0\\u-123\"")))
    }

    @Test fun keysInsideStringValuesAreNotMistakenForFields() {
        // notes 里出现转义过的 "apkUrl": "http://…" —— 真正的 apkUrl 字段在后面
        val notes = "\"见 \\\"apkUrl\\\": \\\"http://evil.example/x.apk\\\"\""
        assertEquals(https, parseLatest(latest(notes = notes))?.apkUrl)
    }

    @Test fun notesAreCappedAt200Chars() {
        val long = "长".repeat(250)
        assertEquals("长".repeat(200), parseLatest(latest(notes = "\"$long\""))?.notes)
    }

    @Test fun notesCapNeverSplitsASurrogatePair() {
        // 199 个普通字符 + 一个 emoji(两个 UTF-16 单元):截在 200 会劈开 emoji,必须整个丢掉
        val long = "a".repeat(199) + "😀" + "b".repeat(10)
        assertEquals("a".repeat(199), parseLatest(latest(notes = "\"$long\""))?.notes)
    }

    // ---- isNewer ----

    private val v3 = LatestInfo(3, "1.0.1", "", https, hex, 28)

    @Test fun isNewerWhenCodeIsHigherAndSdkIsEnough() {
        assertTrue(isNewer(v3, currentCode = 2, sdk = 28))
        assertTrue(isNewer(v3, currentCode = 2, sdk = 34))
    }

    @Test fun notNewerWhenCodeIsEqualOrLower() {
        assertFalse(isNewer(v3, currentCode = 3, sdk = 34))
        assertFalse(isNewer(v3, currentCode = 4, sdk = 34))
    }

    @Test fun notNewerWhenDeviceSdkIsBelowMinSdk() {
        assertFalse(isNewer(v3.copy(minSdk = 30), currentCode = 2, sdk = 29))
    }

    // ---- sha256Hex ----

    @Test fun sha256OfKnownContent() {
        val f = File.createTempFile("sha", ".bin")
        try {
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(f))
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(f))
            // 跨过读缓冲边界(> 64 KiB),确认分块累加没有丢字节
            f.writeBytes(ByteArray(200_000) { (it % 251).toByte() })
            assertEquals(
                java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                    .joinToString("") { "%02x".format(it) },
                sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test fun sha256OfBytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }

    // ---- 更新包身份核对(review Important 1):哈希只证明「包 = latest.json 说的那个」,不证明「包是我们的」 ----

    private val pkg = "com.uniteduone.launcher"
    private val releaseKey = "a".repeat(64)
    private val debugKey = "b".repeat(64)
    private val installed = ApkIdentity(pkg, 2, setOf(releaseKey))
    private val goodArchive = ApkIdentity(pkg, 3, setOf(releaseKey))

    @Test fun samePackageNewerVersionSameSignerIsAccepted() {
        assertNull(checkUpdateApk(goodArchive, installed, expectedVersionCode = 3))
    }

    @Test fun unreadableArchiveOrInstalledInfoIsRejected() {
        assertEquals(UpdateRejection.UNREADABLE, checkUpdateApk(null, installed, 3))
        assertEquals(UpdateRejection.UNREADABLE, checkUpdateApk(goodArchive, null, 3))
    }

    @Test fun otherPackageIsRejectedEvenWhenEverythingElseMatches() {
        assertEquals(
            UpdateRejection.WRONG_PACKAGE,
            checkUpdateApk(goodArchive.copy(packageName = "com.example.other"), installed, 3),
        )
    }

    @Test fun versionMustEqualLatestJsonAndExceedInstalled() {
        assertEquals(UpdateRejection.WRONG_VERSION, checkUpdateApk(goodArchive.copy(versionCode = 2), installed, 3))
        assertEquals(UpdateRejection.WRONG_VERSION, checkUpdateApk(goodArchive.copy(versionCode = 4), installed, 3))
        assertEquals(UpdateRejection.NOT_NEWER, checkUpdateApk(goodArchive, installed.copy(versionCode = 3), 3))
        assertEquals(UpdateRejection.NOT_NEWER, checkUpdateApk(goodArchive, installed.copy(versionCode = 5), 3))
    }

    @Test fun missingSignersFailClosed() {
        assertEquals(UpdateRejection.UNSIGNED, checkUpdateApk(goodArchive.copy(signers = emptySet()), installed, 3))
        assertEquals(UpdateRejection.UNSIGNED, checkUpdateApk(goodArchive, installed.copy(signers = emptySet()), 3))
    }

    @Test fun signerSetsMustBeEqual() {
        assertEquals(UpdateRejection.WRONG_SIGNER, checkUpdateApk(goodArchive.copy(signers = setOf(debugKey)), installed, 3))
        // 多签名:多一个、少一个都不行;顺序无关
        assertEquals(
            UpdateRejection.WRONG_SIGNER,
            checkUpdateApk(goodArchive.copy(signers = setOf(releaseKey, debugKey)), installed, 3),
        )
        assertEquals(
            UpdateRejection.WRONG_SIGNER,
            checkUpdateApk(goodArchive, installed.copy(signers = setOf(releaseKey, debugKey)), 3),
        )
        assertNull(
            checkUpdateApk(
                goodArchive.copy(signers = linkedSetOf(debugKey, releaseKey)),
                installed.copy(signers = linkedSetOf(releaseKey, debugKey)),
                3,
            ),
        )
    }

    // ---- cacheDir/apk 里哪些文件算「更新文件」(清扫只碰这些) ----

    @Test fun updateFileNames() {
        listOf("update.apk", "update-1b2c.apk", "update-1b2c.part", "update-.apk")
            .forEach { assertTrue(it, isUpdateFileName(it)) }
        listOf("upload.apk", "update.apk.bak", "update-1.txt", "notupdate-1.apk", "update", "update.part", "")
            .forEach { assertFalse(it, isUpdateFileName(it)) }
    }

    // ---- 通道地址规则(check 与 parseLatest 共用同一条) ----

    @Test fun allowedUpdateUrls() {
        listOf(
            "https://github.com/GordonWang1878/UnitedU-launcher/releases/latest/download/latest.json",
            "https://bucket-1250000000.cos.ap-shanghai.myqcloud.com/unitedu/latest.json",
            "https://example.com:8443/latest.json?x=1",
            "https://example.com",
            "http://127.0.0.1:8123/latest.json",
            "http://127.0.0.1/latest.json",
            "http://127.0.0.1:8123",
        ).forEach { assertTrue(it, isAllowedUpdateUrl(it)) }
    }

    @Test fun rejectedUpdateUrls() {
        listOf(
            "",
            "http://github.com/latest.json",
            "http://127.0.0.1.evil.com/latest.json",
            "http://127.0.0.1@evil.com/latest.json",
            "http://127.0.0.1:8123@evil.com/latest.json",
            "http://127.0.0.10/latest.json",
            "http://127.0.0.1:/latest.json",
            "http://localhost:8123/latest.json",
            "ftp://example.com/latest.json",
            "https://",
            "https:///latest.json",
            "https://user@example.com/latest.json",
            "https://example.com/a b",
            " https://example.com/latest.json",
            "HTTPS://example.com/latest.json",
            "file:///sdcard/latest.json",
        ).forEach { assertFalse(it, isAllowedUpdateUrl(it)) }
    }

    @Test fun updateUrlsSplitOnCommas() {
        assertEquals(
            listOf("https://a.example/latest.json", "https://b.example/latest.json"),
            parseUpdateUrls(" https://a.example/latest.json , ,https://b.example/latest.json,"),
        )
        assertEquals(emptyList<String>(), parseUpdateUrls(""))
    }

    // ---- 下载百分比 ----

    @Test fun percentOnlyWhenLengthIsKnown() {
        assertNull(downloadPercent(done = 10, total = -1))
        assertNull(downloadPercent(done = 10, total = 0))
        assertEquals(0, downloadPercent(done = 0, total = 1000))
        assertEquals(49, downloadPercent(done = 499, total = 1000))
        assertEquals(100, downloadPercent(done = 1000, total = 1000))
        // 服务器多给了字节也不超过 100(真正的长度核对在下载函数里)
        assertEquals(100, downloadPercent(done = 1500, total = 1000))
        // 大文件不溢出
        assertEquals(50, downloadPercent(done = 3_000_000_000L, total = 6_000_000_000L))
    }

    // ---- resolveLatest:通道顺序与失败原因(spec §7.3) ----

    private val good = latest()
    private val cos = "https://bucket.cos.ap-shanghai.myqcloud.com/unitedu/latest.json"
    private val gh = "https://github.com/GordonWang1878/UnitedU-launcher/releases/latest/download/latest.json"

    @Test fun firstParsableChannelWins() {
        val fetched = mutableListOf<String>()
        val r = resolveLatest(listOf(cos, gh)) { url -> fetched += url; good }
        assertEquals(v3.copy(notes = "修复若干问题"), r.getOrNull())
        assertEquals(listOf(cos), fetched)   // 主通道成功,备用通道不再访问
    }

    @Test fun fallsBackToTheNextChannelOnNetworkFailure() {
        val fetched = mutableListOf<String>()
        val r = resolveLatest(listOf(cos, gh)) { url ->
            fetched += url
            if (url == cos) throw IOException("timeout") else good
        }
        assertEquals(3, r.getOrNull()?.versionCode)
        assertEquals(listOf(cos, gh), fetched)
    }

    @Test fun fallsBackToTheNextChannelOnUnparsableBody() {
        val r = resolveLatest(listOf(cos, gh)) { url -> if (url == cos) "<html>" else good }
        assertEquals(3, r.getOrNull()?.versionCode)
    }

    @Test fun badJsonIsReportedOverNetworkFailure() {
        val r = resolveLatest(listOf(cos, gh)) { url -> if (url == cos) "{\"versionCode\": 3}" else throw IOException("down") }
        assertEquals(CheckFailure.BAD_JSON, (r.exceptionOrNull() as? UpdateCheckException)?.reason)
    }

    @Test fun allChannelsUnreachableIsANetworkFailure() {
        val r = resolveLatest(listOf(cos, gh)) { throw IOException("down") }
        assertEquals(CheckFailure.NETWORK, (r.exceptionOrNull() as? UpdateCheckException)?.reason)
        // 非 IOException 的意外(比如 URL 构造异常)同样算网络失败,不能冒泡成崩溃
        val r2 = resolveLatest(listOf(cos)) { throw IllegalStateException("boom") }
        assertEquals(CheckFailure.NETWORK, (r2.exceptionOrNull() as? UpdateCheckException)?.reason)
    }

    @Test fun disallowedChannelsAreNeverFetched() {
        val fetched = mutableListOf<String>()
        val r = resolveLatest(listOf("http://example.com/latest.json", gh)) { url -> fetched += url; good }
        assertEquals(listOf(gh), fetched)
        assertTrue(r.isSuccess)
    }

    @Test fun noUsableChannelIsANetworkFailure() {
        assertEquals(
            CheckFailure.NETWORK,
            (resolveLatest(emptyList()) { good }.exceptionOrNull() as? UpdateCheckException)?.reason,
        )
        assertEquals(
            CheckFailure.NETWORK,
            (resolveLatest(listOf("http://example.com/latest.json")) { good }.exceptionOrNull() as? UpdateCheckException)?.reason,
        )
    }

    @Test fun failuresAreLoggedPerChannel() {
        val log = mutableListOf<String>()
        resolveLatest(listOf("http://example.com/x", cos, gh), log = { log += it }) { url ->
            if (url == cos) throw IOException("timeout") else "nope"
        }
        assertEquals(3, log.size)
        assertTrue(log[0], log[0].contains("http://example.com/x"))
        assertTrue(log[1], log[1].contains(cos) && log[1].contains("timeout"))
        assertTrue(log[2], log[2].contains(gh))
    }
}
