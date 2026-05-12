package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * 伙伴猎-v3 策略插件
 */
class PartnerHunterPlugin : StrategyPlugin {
    override fun description(): String = "伙伴猎-v3：身材效率DP+贪婪填充+主动解场+升级牌优先+发现评分+威胁评估"

    override fun author(): String = "Demon-0404"

    override fun version(): String = VersionInfo.VERSION

    override fun id(): String = "partner-hunter-v3"

    override fun name(): String = "伙伴猎-v3"

    override fun homeUrl(): String = "https://github.com/Demon-0404/Hearthstone-Script-Test"

    override fun cardSDKVersion(): String? =
        if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.CARD_SDK_VERSION_USED

    override fun strategySDKVersion(): String? =
        if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.STRATEGY_SDK_VERSION_USED
}
