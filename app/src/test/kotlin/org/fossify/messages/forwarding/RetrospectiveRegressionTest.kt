package org.fossify.messages.forwarding

import android.content.SharedPreferences
import org.fossify.messages.autofill.VerificationCodeExtractor
import org.fossify.messages.helpers.YellowPageHelper
import org.fossify.messages.observability.log.RingBufferLogManager
import org.fossify.messages.remote.RemoteSmsCommand
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.template.TemplateRenderer
import org.fossify.messages.services.IncomingSmsService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Calendar
import javax.net.ssl.SSLException

class RetrospectiveRegressionTest {

    @Test
    fun smsDirectWorkerSourceNormalizesNotificationFallbackSuffixes() {
        val digest = IncomingSmsService.rawBodyDigest16("【测试】验证码 123456")
        assertEquals(16, digest.length)
        val base = "sms-notify-1700000000000-$digest-1"
        val routeA = SmsDirectBillingPolicy.workerSource("$base-instA", 1700000000000L, "fb1")
        val routeB = SmsDirectBillingPolicy.workerSource("$base-rule1234-act5678", 1700000000000L, "fb2")
        assertEquals("worker:$base:1700000000000", routeA)
        assertEquals(routeA, routeB)
    }

    @Test
    fun smsDirectOutboundLoopAllowsMultiRecipientSameSmsAndBlocksCarrierEcho() {
        val rawBody = "【阿里云】您的验证码为 884812，5分钟内有效。"
        val renderedSameAsBody = rawBody
        val sourceSms1 = "worker:sms-500:1700000000000"
        val sourceSms2 = "worker:sms-501:1700000003000"

        SmsDirectSendGuard.recordOutboundContent(renderedSameAsBody, sourceSms1, nowMs = 1000L)

        // Recipient #2 of the SAME incoming SMS must NOT be blocked even if template == {{BODY}}
        assertFalse(SmsDirectSendGuard.isRecentOutboundLoop(rawBody, sourceSms1, nowMs = 1050L))

        // Carrier echo arriving as a NEW incoming SMS with identical text MUST be blocked
        assertTrue(SmsDirectSendGuard.isRecentOutboundLoop(rawBody, sourceSms2, nowMs = 3000L))

        // Standard formatted template must NOT block a 2nd legitimate identical raw alert
        val rawAlert = "【监控】CPU 使用率超过 90%"
        val renderedAlert = "📩新短信通知\n$rawAlert\n⏰时间：2026-11-18 14:00:00"
        SmsDirectSendGuard.recordOutboundContent(renderedAlert, "worker:sms-600:2000", nowMs = 2000L)
        assertFalse(SmsDirectSendGuard.isRecentOutboundLoop(rawAlert, "worker:sms-601:5000", nowMs = 5000L))
        assertTrue(SmsDirectSendGuard.isRecentOutboundLoop(renderedAlert, "worker:sms-601:5000", nowMs = 5000L))
    }

    @Test
    fun crossPathPairedDedupeConservesAllIdenticalSmsInBurst() {
        val body = "【银行】动态验证码 654321，请勿泄露。"
        val t0 = 5_000_000L

        // SMS #1: Notification arrives at T=0ms, then dual broadcasts (DELIVER + RECEIVED) arrive at T=80ms
        assertTrue(IncomingSmsService.claimNotificationSmsForward(body, t0))
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_DELIVER", "95555", body, 1001L, t0 + 80L
        )
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_RECEIVED", "95555", body, 1001L, t0 + 85L
        )
        assertTrue(IncomingSmsService.consumeNotificationForwardMatch(body, t0 + 120L))

        // SMS #2 (identical OTP resent 2 seconds later!): Broadcast arrives first, then Notification
        val t2 = t0 + 2_000L
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_DELIVER", "95555", body, 1002L, t2
        )
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_RECEIVED", "95555", body, 1002L, t2 + 5L
        )
        assertFalse(IncomingSmsService.claimNotificationSmsForward(body, t2 + 30L))
        assertFalse(IncomingSmsService.consumeNotificationForwardMatch(body, t2 + 60L))

        // SMS #3 (identical OTP 1 second later via Notification only): must forward!
        val t3 = t2 + 1_000L
        assertTrue(IncomingSmsService.claimNotificationSmsForward(body, t3))
    }

    @Test
    fun privacyMaskerDoesNotCorruptYearInTimestampWhenOtpMatchesYear() {
        val text = "验证码：2026\n接收时间：2026-11-18 14:30:00"
        val masked = PrivacyDataMasker.mask(
            content = text,
            maskVerificationCode = true,
            verificationCode = "2026",
        )
        assertTrue(masked.contains("验证码：[******]"))
        assertTrue(masked.contains("2026-11-18 14:30:00"))
    }

    @Test
    fun ruleEngineRespectsSmsDirectScopeOnV2Actions() {
        val rule = ForwardingRule(
            name = "验证码规则",
            enabled = true,
            conditions = listOf(
                ForwardingRuleCondition(
                    field = RuleTargetField.BODY,
                    operator = RuleOperator.CONTAINS,
                    value = "验证码",
                )
            ),
            actions = listOf(
                ForwardingRuleAction(channelType = ForwardingChannels.DINGTALK, targetInstanceId = "dt1"),
                ForwardingRuleAction(channelType = ForwardingChannels.SMS_DIRECT, targetInstanceId = "sms1"),
            ),
        )
        val engine = ForwardingRuleEngine(listOf(rule))

        val networkOnly = engine.evaluate(
            sender = "10086",
            body = "验证码 123456",
            subscriptionId = 1,
            channelCandidates = setOf(ForwardingChannels.DINGTALK),
        )
        assertEquals(listOf("dt1"), networkOnly.targets.map { it.instanceId })

        val withSmsDirect = engine.evaluate(
            sender = "10086",
            body = "验证码 123456",
            subscriptionId = 1,
            channelCandidates = setOf(ForwardingChannels.DINGTALK, ForwardingChannels.SMS_DIRECT),
        )
        assertEquals(listOf("dt1", "sms1"), withSmsDirect.targets.map { it.instanceId })
    }

    @Test
    fun verificationExtractorHandlesEnglishWordsLeadingBracketsAndCjkBoundaries() {
        assertEquals(
            "184920",
            VerificationCodeExtractor.extractCode("Your verification code from CloudService is 184920.")
        )
        assertEquals(
            "123456",
            VerificationCodeExtractor.extractCode("【123456】是您的登录验证码，5分钟内有效")
        )
        assertEquals(
            "654321",
            VerificationCodeExtractor.extractCode("您的验证码是[654321]")
        )
        assertEquals(
            "889900",
            VerificationCodeExtractor.extractCode("您的验证码是【889900】【中国移动】")
        )
        assertEquals(
            "776655",
            VerificationCodeExtractor.extractCode("【银行】动态验证码:776655有效，切勿泄露")
        )
        assertEquals(
            "345678",
            VerificationCodeExtractor.extractCode("您正在修改尾号8888卡的交易密码，本次验证码345678。")
        )
        assertNull(
            VerificationCodeExtractor.extractCode("Please decode the unicode file 123456 before spinning up.")
        )
    }

    @Test
    fun singlePassTemplateRendererBlocksSecondOrderPlaceholderInjection() {
        val ctx = IncomingMessageContext(
            sender = "10690000",
            body = "攻击载荷 {{SENDER}} {{TIME}} {{SIM}} 验证码 998877",
            subscriptionId = 0,
            timestamp = 1700000000000L,
        )
        val rendered = TemplateRenderer.render("正文={{BODY}} 码={{CODE}} 发件人={{SENDER}}", ctx)
        assertTrue(rendered.contains("攻击载荷 {{SENDER}} {{TIME}} {{SIM}} 验证码 998877"))
        assertTrue(rendered.contains("码=998877"))
        assertTrue(rendered.contains("发件人=10690000"))
    }

    @Test
    fun remoteCommandParserHandlesSpacedMentionsAndTelegramBotSuffix() {
        val stripped = RemoteSmsCommand.stripLeadingMentionBeforePrefix("@SMS Bot\u2005/短信发送 13800138000 你好")
        val cmd1 = RemoteSmsCommand.parse(stripped)
        assertNotNull(cmd1)
        assertEquals("13800138000", cmd1!!.targetNumber)
        assertEquals("你好", cmd1.content)

        val cmd2 = RemoteSmsCommand.parse("/发信@my_gateway_bot SIM2 13900139000 测试内容")
        assertNotNull(cmd2)
        assertEquals("13900139000", cmd2!!.targetNumber)
        assertEquals("测试内容", cmd2.content)

        val nonLeading = RemoteSmsCommand.stripLeadingMentionBeforePrefix("普通聊天顺便提一句 /发信 13800138000 你好")
        assertNull(RemoteSmsCommand.parse(nonLeading))
    }

    @Test
    fun dndCrossMidnightAssignsEarlyMorningToPreviousWeekdayAndRejectsEmptyDays() {
        val engine = ForwardingRuleEngine(emptyList())
        val calFriNight = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 23, 0, 0) // Friday 23:00
        }
        val calSatEarly = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 3, 0, 0) // Saturday 03:00 (Friday overnight!)
        }
        val calFriEarly = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 3, 0, 0) // Friday 03:00 (Thursday overnight!)
        }

        assertFalse(engine.isTimeRangeActive("22:00", "06:00", emptyList(), calFriNight.timeInMillis))
        assertTrue(engine.isTimeRangeActive("22:00", "06:00", listOf(5), calFriNight.timeInMillis))
        assertTrue(engine.isTimeRangeActive("22:00", "06:00", listOf(5), calSatEarly.timeInMillis))
        assertFalse(engine.isTimeRangeActive("22:00", "06:00", listOf(5), calFriEarly.timeInMillis))
    }

    @Test
    fun yellowPageProtectsInternationalNumbersAndIgnoresPureDigitBrackets() {
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("+14155552671"))
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("+85291234567"))
        assertNull(YellowPageHelper.extractSignature("您的登录验证码是[123456]"))
        assertEquals("中国移动", YellowPageHelper.extractSignature("您的验证码是【123456】【中国移动】"))
    }

    @Test
    fun preSubmitAndTlsFailuresAreRetryableWhilePostWriteIoIsUnknown() {
        assertFalse(DeliveryFailurePolicy.isUnknown(PreSubmitNetworkFailure(IOException("oauth token fetch timeout"))))
        assertFalse(DeliveryFailurePolicy.isUnknown(SSLException("TLS handshake aborted")))
        assertTrue(DeliveryFailurePolicy.isUnknown(IOException("socket reset while reading HTTP 200 response")))
    }

    @Test
    fun releasingNotificationClaimRestoresBroadcastForwardingBeforeAndAfterPairing() {
        val bodyA = "【通知回滚A】验证码 112233"
        val tA = 9_000_000L
        // Case 1: Notification claims, fails/defers (0 targets or missing phone), releases BEFORE broadcast arrives
        assertTrue(IncomingSmsService.claimNotificationSmsForward(bodyA, tA))
        IncomingSmsService.releaseNotificationSmsForward(bodyA)
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_DELIVER", "95555", bodyA, 2001L, tA + 50L
        )
        assertFalse(IncomingSmsService.consumeNotificationForwardMatch(bodyA, tA + 80L))

        val bodyB = "【通知回滚B】验证码 445566"
        val tB = 9_100_000L
        // Case 2: Notification claims, broadcast arrives and pairs it while notification is evaluating, then notification releases!
        assertTrue(IncomingSmsService.claimNotificationSmsForward(bodyB, tB))
        IncomingSmsService.onSmsBroadcastReceived(
            "android.provider.Telephony.SMS_DELIVER", "95555", bodyB, 2002L, tB + 20L
        )
        IncomingSmsService.releaseNotificationSmsForward(bodyB)
        assertFalse(IncomingSmsService.consumeNotificationForwardMatch(bodyB, tB + 60L))
    }

    @Test
    fun ringBufferClearResetsCapacityAndMasksSensitiveData() {
        RingBufferLogManager.clear()
        for (idx in 1..5) {
            RingBufferLogManager.i("TestTag", "item-$idx")
        }
        assertEquals(5, RingBufferLogManager.size())
        RingBufferLogManager.clear()
        assertEquals(0, RingBufferLogManager.size())

        RingBufferLogManager.i(
            "Forwarder",
            "send to +8613812345678 via https://api.example.com/push?access_token=sec_998877&uid=1 验证码：4829"
        )
        assertEquals(1, RingBufferLogManager.size())
        val msg = RingBufferLogManager.getRecentLogs(1).single().message
        assertTrue(msg.contains("+86138****5678"))
        assertTrue(msg.contains("access_token=[REDACTED]"))
        assertFalse(msg.contains("sec_998877"))
        assertFalse(msg.contains("4829"))
        RingBufferLogManager.clear()
    }

    @Test
    fun classicDecodedRulesPreserveSenderAndKeywordOrWithRegexAndExplicitSmsDirectScope() {
        val prefs = createFakeSharedPrefs()
        val rulesConfig = ForwardingRulesConfig(customPrefs = prefs)
        rulesConfig.scope = ForwardingRulesConfig.SCOPE_FORWARDING_ONLY

        val classicJson = JSONArray().put(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", "classic-1")
                .put("name", "经典发件人+多关键词正则")
                .put("enabled", true)
                .put("matchMode", ForwardingRule.MATCH_ALL)
                .put("includeKeywords", JSONArray(listOf("95588", "95533")))
                .put("includeRegex", "\\d{6}")
                .put("channels", JSONArray(listOf(ForwardingChannels.DINGTALK)))
        ).toString()

        val decoded = rulesConfig.decodeRules(classicJson)
        assertEquals(1, decoded.size)
        assertTrue("经典规则不应被有损改写为仅 BODY 条件", decoded.single().conditions.isEmpty())

        val engine = ForwardingRuleEngine(decoded)
        // Sender matches "95588" (one of includeKeywords) AND body matches \d{6} -> MUST match!
        val matched = engine.evaluate(
            sender = "95588",
            body = "您的交易动态码为 654321",
            subscriptionId = 1,
            channelCandidates = setOf(ForwardingChannels.DINGTALK, ForwardingChannels.SMS_DIRECT)
        )
        assertEquals(1, matched.targets.size)

        // Non-bound SCOPE_FORWARDING_ONLY excludes SMS_DIRECT from rule candidates
        rulesConfig.rules = decoded
        assertFalse(
            ForwardingChannels.SMS_DIRECT in rulesConfig.channelCandidatesForScope(
                setOf(ForwardingChannels.DINGTALK, ForwardingChannels.SMS_DIRECT)
            )
        )

        // Explicitly binding SMS_DIRECT on an enabled rule includes SMS_DIRECT in rule candidates
        rulesConfig.rules = listOf(
            decoded.single().copy(
                actions = listOf(
                    ForwardingRuleAction(channelType = ForwardingChannels.SMS_DIRECT, targetInstanceId = "inst_sms_1")
                )
            )
        )
        assertTrue(
            ForwardingChannels.SMS_DIRECT in rulesConfig.channelCandidatesForScope(
                setOf(ForwardingChannels.DINGTALK, ForwardingChannels.SMS_DIRECT)
            )
        )
    }

    @Test
    fun ntfyAsciiHeaderSanitizesNonAsciiAndRemoteCrossPathDedupePreventsDoubleBilling() {
        assertEquals("warning,rotating_light", NtfyProtocol.tagsHeader("  warning, 告警 , rotating_light\n "))
        assertEquals("", NtfyProtocol.tagsHeader("告警,通知"))
        assertEquals("https://example.com/view", NtfyProtocol.asciiHeaderValue("  https://example.com/view\r\n "))

        val config = RemoteSmsCommandConfig(customPrefs = createFakeSharedPrefs())
        val t0 = 1_700_000_000_000L
        assertFalse(config.hasRecentCrossPathCommand("+8613800138000", "10086", "CXLL", now = t0))
        assertTrue(config.claimCrossPathCommand("+8613800138000", "10086", "CXLL", now = t0))
        // Broadcast arriving 120ms later with normalized sender without +86 must be recognized as duplicate
        assertTrue(config.hasRecentCrossPathCommand("13800138000", "10086", "CXLL", now = t0 + 120L))
        assertFalse(config.claimCrossPathCommand("13800138000", "10086", "CXLL", now = t0 + 120L))
        // Same sender issuing a new command after the 15s cross-path window is allowed
        assertTrue(config.claimCrossPathCommand("13800138000", "10086", "CXLL", now = t0 + 16_000L))
    }

    private fun createFakeSharedPrefs(initialPrefs: Map<String, Any> = emptyMap()): SharedPreferences {
        val prefsMap = HashMap<String, Any>(initialPrefs)
        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val k = args[0] as String
                    val v = args[1] as? String
                    if (v != null) prefsMap[k] = v else prefsMap.remove(k)
                    proxy
                }
                "putBoolean" -> {
                    prefsMap[args[0] as String] = args[1] as Boolean
                    proxy
                }
                "putInt" -> {
                    prefsMap[args[0] as String] = args[1] as Int
                    proxy
                }
                "remove" -> {
                    prefsMap.remove(args[0] as String)
                    proxy
                }
                "apply", "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> prefsMap[args[0] as String] ?: (args[1] as? String)
                "getBoolean" -> prefsMap[args[0] as String] ?: (args[1] as? Boolean) ?: false
                "getInt" -> prefsMap[args[0] as String] ?: (args[1] as? Int) ?: 0
                "getLong" -> prefsMap[args[0] as String] ?: (args[1] as? Long) ?: 0L
                "contains" -> prefsMap.containsKey(args[0] as String)
                "edit" -> editorProxy
                "getAll" -> prefsMap
                else -> null
            }
        } as SharedPreferences
    }
}
