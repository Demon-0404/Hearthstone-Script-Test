package club.xiaojiawei.facehunter

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * 快攻猎-v1 策略插件
 */
class FaceHunterPlugin : StrategyPlugin {
    override fun description(): String = "快攻猎-v1：极限抢脸+直伤斩杀+低费铺场+英雄技能节奏+三姐妹协同"

    override fun author(): String = "Demon-0404"

    override fun version(): String = VersionInfo.VERSION

    override fun id(): String = "face-hunter-v1"

    override fun name(): String = "快攻猎-v1"

    override fun homeUrl(): String = "https://github.com/Demon-0404/Hearthstone-Script-Test"

    override fun cardSDKVersion(): String? =
        if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.CARD_SDK_VERSION_USED

    override fun strategySDKVersion(): String? =
        if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null
        else VersionInfo.STRATEGY_SDK_VERSION_USED
}
