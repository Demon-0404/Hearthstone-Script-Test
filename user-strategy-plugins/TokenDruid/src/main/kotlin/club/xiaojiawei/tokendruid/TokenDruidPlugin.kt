package club.xiaojiawei.tokendruid

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * 铺场德-v2 策略插件
 */
class TokenDruidPlugin : StrategyPlugin {
    override fun description(): String = "铺场德-v2：跳费铺场+群体buff+地标协同+亡语赖场"

    override fun author(): String = "Demon-0404"

    override fun version(): String = VersionInfo.VERSION

    override fun id(): String = "token-druid-v2"

    override fun name(): String = "铺场德-v2"

    override fun homeUrl(): String = "https://github.com/Demon-0404/Hearthstone-Script-Test"

    override fun cardSDKVersion(): String? =
        if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.CARD_SDK_VERSION_USED

    override fun strategySDKVersion(): String? =
        if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.STRATEGY_SDK_VERSION_USED
}
