package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * 伙伴猎-v2 策略插件
 */
class PartnerHunterPlugin : StrategyPlugin {
    override fun description(): String = "伙伴猎-v2专属策略：DP出牌(不过滤战吼)+伙伴猎排序(升级→双倍→召唤)+cleanPlay解场+野兽协同评分器"

    override fun author(): String = "Demon-0404"

    override fun version(): String = VersionInfo.VERSION

    override fun id(): String = "partner-hunter-v2"

    override fun name(): String = "伙伴猎-v2"

    override fun homeUrl(): String = "https://github.com/Demon-0404/Hearthstone-Script-Test"

    override fun cardSDKVersion(): String? =
        if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.CARD_SDK_VERSION_USED

    override fun strategySDKVersion(): String? =
        if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.STRATEGY_SDK_VERSION_USED
}
