package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent

/**
 * 伙伴猎-v2 混合策略 — 伙伴猎-大狼
 *
 * 出牌：DeckStrategyUtil DP背包 + autoPower（激进策略模式，不依赖卡牌行为数据）
 * 解场：DeckStrategyUtil.cleanPlay() 多线程递归遍历最优交换
 * 换牌/发现/回溯：统一评分器驱动
 */
class PartnerHunterDeck : DeckStrategy() {

    override fun name(): String = "伙伴猎-v2"

    override fun description(): String =
        "混合策略：DP出牌+cleanPlay解场+评分器驱动发现/回溯/换牌。已适配伙伴猎-大狼。"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAfWhBwidoASpgQfDgweZpweapwebpwfLtgfkxAcLqZ8EidQE4okHr5IH7p8Hu8AH3sQH4MQH48QH+cQHof0HAAA="

    override fun id(): String = "partner-hunter-deck-v2"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    // ==================== 换牌策略 ====================

    override fun executeChangeCard(cards: HashSet<Card>) {
        val me = WAR.me
        val isGoingFirst = me.handArea.cards.size <= 3

        for (card in cards.toList()) {
            val score = scoreCardForKeep(card, isGoingFirst)
            if (score < 0.3) {
                cards.remove(card)
            }
        }
    }

    private fun scoreCardForKeep(card: Card, isGoingFirst: Boolean): Double {
        var score = 0.0
        val maxCost = if (isGoingFirst) 3 else 4

        // 费用适配
        if (card.cost in 1..maxCost) {
            score += 0.4
        } else if (card.cost > maxCost) {
            score -= 0.3
        }

        // 野兽协同
        if (card.cardRace == CardRaceEnum.PET) {
            score += 0.2
        }

        // 读卡牌权重
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.3
            score += cardData.changeWeight * 0.2
        }

        // 法术不友好
        if (card.cardType == CardTypeEnum.SPELL) {
            score -= 0.15
        }
        if (card.cardType == CardTypeEnum.WEAPON) {
            score += 0.1
        }

        return score
    }

    // ==================== 出牌策略 ====================

    override fun executeOutCard() {
        val me = WAR.me
        if (!me.isValid()) return
        val rival = WAR.rival
        if (!rival.isValid()) return

        val heroPower = me.playArea.power

        // 1. 使用地标
        DeckStrategyUtil.activeLocation(me.playArea.cards.toList())

        // 2. 计算最优出牌顺序 + 打出（激进策略的核心逻辑）
        DeckStrategyUtil.powerCard(me, rival)

        // 3. 解场（多线程递归清场）
        DeckStrategyUtil.cleanPlay()

        // 4. 再次尝试出牌（清场后可能有位置了）
        DeckStrategyUtil.powerCard(me, rival)

        // 5. 地标二次使用
        DeckStrategyUtil.activeLocation(me.playArea.cards.toList())

        // 6. 英雄技能：有多余水晶且无更好操作时打脸
        heroPower?.let { power ->
            if (power.cost <= me.usableResource) {
                val hasPlayableCards = me.handArea.cards.any {
                    it.cost <= me.usableResource &&
                            (it.cardType == CardTypeEnum.MINION ||
                                    it.cardType == CardTypeEnum.SPELL ||
                                    it.cardType == CardTypeEnum.WEAPON)
                }
                if (!hasPlayableCards || me.usableResource >= power.cost + 2) {
                    log.info { "使用英雄技能打脸" }
                    power.action.power()
                    Thread.sleep(800)
                }
            }
        }

        // 7. 使用法力渴求/激发类技能
        me.playArea.cards.toList().forEach { card ->
            if (card.isLaunchpad && me.usableResource >= card.launchCost()) {
                card.action.launch()
                Thread.sleep(800)
            }
        }
    }

    // ==================== 发现选牌策略 ====================

    override fun executeDiscoverChooseCard(vararg cards: Card): Int {
        val me = WAR.me

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for ((index, card) in cards.withIndex()) {
            val score = scoreDiscoverCard(card, me)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        return bestIndex
    }

    private fun scoreDiscoverCard(
        card: Card,
        me: club.xiaojiawei.hsscriptcardsdk.bean.Player,
    ): Double {
        var score = 0.0

        // 即时可用性
        if (card.cost <= me.usableResource) {
            score += 0.3
        } else if (card.cost > me.usableResource + 2) {
            score -= 0.2
        }

        // 野兽协同
        if (card.cardRace == CardRaceEnum.PET) {
            score += 0.25
        }

        // 卡牌特征
        when (card.cardType) {
            CardTypeEnum.MINION -> {
                if (card.isTaunt) score += 0.2
                if (card.isPoisonous) score += 0.15
                if (card.isLifesteal) score += 0.2
                if (card.isRush || card.isCharge) score += 0.15
                if (card.cost > 0) {
                    score += (card.health + card.atc).toDouble() / card.cost * 0.05
                }
            }
            CardTypeEnum.SPELL -> score += 0.1
            CardTypeEnum.WEAPON -> score += 0.15
            else -> {}
        }

        // 权重
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.2
        }

        return score
    }

    // ==================== 回溯策略 ====================

    override fun execChooseTimeLine(timeLineEvent: TimelineEvent) {
        val me = WAR.me
        val boardScore = scoreCurrentBoard(me)

        if (boardScore >= 0.4) {
            timeLineEvent.keep()
        } else {
            timeLineEvent.rewind()
        }
    }

    private fun scoreCurrentBoard(me: club.xiaojiawei.hsscriptcardsdk.bean.Player): Double {
        var score = 0.5
        val hero = me.playArea.hero ?: return 0.3
        val maxHp = hero.health + hero.armor
        val currentHp = maxHp - hero.damage

        val hpRatio = (currentHp.toDouble() / maxHp).coerceIn(0.0, 1.0)
        score += (hpRatio - 0.5) * 0.3

        val minionCount = me.playArea.cards.size
        score += (minionCount - 2).coerceIn(-2, 3) * 0.08

        val handCount = me.handArea.cards.size
        score += (handCount - 3).coerceIn(-3, 3) * 0.05

        if (me.usableResource >= 5) score += 0.1

        return score.coerceIn(0.0, 1.0)
    }

    override fun reset() {
        super.reset()
    }
}
