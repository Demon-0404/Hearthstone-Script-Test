package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * 伙伴猎策略插件
 */
class PartnerHunterPlugin : StrategyPlugin {
    override fun description(): String = "伙伴猎卡组策略，围绕伙伴体系和野兽随从进行快攻打脸"

    override fun author(): String = "自定义插件"

    override fun version(): String = VersionInfo.VERSION

    override fun id(): String = "partner-hunter-strategy"

    override fun name(): String = "伙伴猎策略"

    override fun homeUrl(): String = ""

    override fun cardSDKVersion(): String? =
        if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.CARD_SDK_VERSION_USED

    override fun strategySDKVersion(): String? =
        if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.STRATEGY_SDK_VERSION_USED
}
