package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent

/**
 * 伙伴猎-v2 混合策略 — 伙伴猎专属优化版
 *
 * 核心改动（相比v1）：
 * - 不再使用 powerCard()（会过滤战吼），改用 calcPowerOrderConvert 直接 DP 出牌
 * - 出牌前按伙伴猎逻辑排序：升级牌 → 双倍战吼牌 → 召唤牌 → 其他
 * - 换牌/发现/回溯 均针对野兽协同和动物伙伴等级做优化
 */
class PartnerHunterDeck : DeckStrategy() {

    override fun name(): String = "伙伴猎-v2"

    override fun description(): String =
        "伙伴猎专属策略：DP出牌(不过滤战吼)+cleanPlay解场+伙伴猎排序+野兽协同评分器"

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
            if (score < 0.35) {
                cards.remove(card)
            }
        }
    }

    private fun scoreCardForKeep(card: Card, isGoingFirst: Boolean): Double {
        var score = 0.0
        val maxCost = if (isGoingFirst) 3 else 4

        // 1费启动牌 — 伙伴猎命脉
        if (card.cost == 1) {
            score += 0.5
        }

        // 费用适配
        if (card.cost in 2..maxCost) {
            score += 0.3
        } else if (card.cost > maxCost) {
            score -= 0.35
        }

        // 野兽协同
        if (card.cardRace == CardRaceEnum.PET) {
            score += 0.2
        }

        // 战吼牌加分（伙伴猎核心全是战吼）
        if (card.isBattlecry) {
            score += 0.15
        }

        // 法术 — 追踪术/击伤猎物等留着有用
        if (card.cardType == CardTypeEnum.SPELL && card.cost <= 2) {
            score += 0.1
        }

        // 高费法术不留
        if (card.cardType == CardTypeEnum.SPELL && card.cost >= 5) {
            score -= 0.3
        }

        // 武器加分（凯旋等）
        if (card.cardType == CardTypeEnum.WEAPON) {
            score += 0.1
        }

        // 卡牌权重
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.3
            score += cardData.changeWeight * 0.25
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
        var plays = me.playArea.cards.toList()

        // 1. 使用地标
        DeckStrategyUtil.activeLocation(plays)

        // 2. DP 背包计算出牌（不过滤战吼/法术，参考 HsRadicalDeckStrategy）
        val hands = me.handArea.cards.toList()
        val myHandCardsCopy = hands.toMutableList()
        myHandCardsCopy.removeAll { card -> card.isCoinCard }

        val (score, resultCards) = DeckStrategyUtil.calcPowerOrderConvert(myHandCardsCopy, me.usableResource)

        var finalCards = resultCards
        val coinCard = DeckStrategyUtil.findCoin(hands)
        if (coinCard != null) {
            val (coinScore, coinResultCards) = DeckStrategyUtil.calcPowerOrderConvert(
                myHandCardsCopy,
                me.usableResource + 1,
            )
            if (coinScore > score) {
                coinCard.action.power()
                Thread.sleep(1000)
                finalCards = coinResultCards
            }
        }

        // 3. 伙伴猎专属排序：升级牌 → 双倍牌 → 召唤牌 → 其他随从 → 法术
        if (finalCards.isNotEmpty()) {
            DeckStrategyUtil.updateTextForCard(finalCards)
            val sorted = sortPartnerHunterCards(finalCards)
            log.info { "待出牌:${sorted.size}张\n${sorted.joinToString("\n")}" }

            for (simulateWeightCard in sorted) {
                val card = simulateWeightCard.card
                if (me.usableResource >= card.cost) {
                    if (card.cardType === CardTypeEnum.SPELL || card.cardType === CardTypeEnum.HERO) {
                        card.action.autoPower(CARD_DATA_TRIE[card.cardId])
                    } else {
                        if (me.playArea.isFull) break
                        card.action.autoPower(CARD_DATA_TRIE[card.cardId])
                    }
                }
            }
        }

        // 4. 解场（多线程递归清场）
        DeckStrategyUtil.cleanPlay()

        // 5. 地标二次使用
        plays = me.playArea.cards.toList()
        DeckStrategyUtil.activeLocation(plays)

        // 6. 清场后补牌（powerCard 此时打白板随从没问题）
        DeckStrategyUtil.powerCard(me, rival)

        // 7. 英雄技能
        heroPower?.let { power ->
            if (me.usableResource >= power.cost) {
                val hasPlayable = me.handArea.cards.any {
                    it.cost <= me.usableResource &&
                        (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.SPELL)
                }
                if (!hasPlayable || me.usableResource >= power.cost + 2) {
                    log.info { "使用英雄技能" }
                    power.action.power()
                    Thread.sleep(800)
                }
            }
        }

        // 8. 法力渴求/激发
        me.playArea.cards.toList().forEach { card ->
            if (card.isLaunchpad && me.usableResource >= card.launchCost()) {
                card.action.launch()
                Thread.sleep(800)
            }
        }
    }

    /**
     * 伙伴猎出牌排序：升级牌 → 双倍战吼牌 → 野兽召唤牌 → 其他随从 → 武器 → 法术
     *
     * 排序依据卡牌可观测特征（不依赖具体 cardId）：
     * - 升级牌：低费战吼随从，攻击力很低（驯服宠物/雷象/自由漫步等）
     * - 双倍战吼牌：中费战吼随从，身材中等（塔雅·陆行）
     * - 野兽召唤牌：野兽种族（动物伙伴、兽群呼唤等）
     */
    private fun sortPartnerHunterCards(
        cards: List<club.xiaojiawei.hsscriptbasestrategy.bean.SimulateWeightCard>,
    ): List<club.xiaojiawei.hsscriptbasestrategy.bean.SimulateWeightCard> {
        return cards.sortedBy { card ->
            val c = card.card
            when {
                // 0费牌最先打
                c.cost == 0 -> 0
                // 地标优先
                c.cardType == CardTypeEnum.LOCATION -> 1
                // 升级牌：低费(1-3)、战吼、低攻(0-1) — 驯服宠物/雷象/直面无面者
                c.cost in 1..3 && c.isBattlecry && c.atc <= 1 -> 10
                // 双倍战吼牌：4-5费、战吼 — 塔雅·陆行/灵语猎手
                c.cost in 4..5 && c.isBattlecry -> 20
                // 野兽召唤牌：野兽种族随从 — 动物伙伴召唤的/高费野兽
                c.cardRace == CardRaceEnum.PET && c.cardType == CardTypeEnum.MINION -> 30
                // 武器
                c.cardType == CardTypeEnum.WEAPON -> 40
                // 其他随从
                c.cardType == CardTypeEnum.MINION -> 50
                // 法术最后
                c.cardType == CardTypeEnum.SPELL -> 60
                else -> 100
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

    private fun scoreDiscoverCard(card: Card, me: Player): Double {
        var score = 0.0

        // 即时可用性
        if (card.cost <= me.usableResource) {
            score += 0.3
        } else if (card.cost > me.usableResource + 3) {
            score -= 0.25
        }

        // 野兽协同（核心）
        if (card.cardRace == CardRaceEnum.PET) {
            score += 0.3
        }

        // 战吼牌加分（伙伴猎体系）
        if (card.isBattlecry) {
            score += 0.1
        }

        // 升级牌特征：低费低攻战吼 → 高优先级
        if (card.cost <= 3 && card.atc <= 1 && card.isBattlecry) {
            score += 0.2
        }

        // 随从特征评分
        when (card.cardType) {
            CardTypeEnum.MINION -> {
                if (card.isTaunt) score += 0.2
                if (card.isPoisonous) score += 0.15
                if (card.isLifesteal) score += 0.2
                if (card.isRush || card.isCharge) score += 0.2
                // 突袭+野兽 = 强力解场
                if ((card.isRush || card.isCharge) && card.cardRace == CardRaceEnum.PET) {
                    score += 0.15
                }
                // 身材效率
                if (card.cost > 0) {
                    score += (card.health + card.atc).toDouble() / card.cost * 0.05
                }
            }
            CardTypeEnum.SPELL -> {
                // 低费法术（追踪术、击伤猎物）加分
                if (card.cost <= 2) score += 0.2
                else score += 0.05
            }
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

    private fun scoreCurrentBoard(me: Player): Double {
        var score = 0.5
        val hero = me.playArea.hero ?: return 0.3
        val maxHp = hero.health + hero.armor
        val currentHp = maxHp - hero.damage

        // 血量健康度
        val hpRatio = (currentHp.toDouble() / maxHp).coerceIn(0.0, 1.0)
        score += (hpRatio - 0.5) * 0.3

        // 场面随从数量
        val minionCount = me.playArea.cards.size
        score += (minionCount - 2).coerceIn(-2, 3) * 0.08

        // 手牌资源
        val handCount = me.handArea.cards.size
        score += (handCount - 3).coerceIn(-3, 3) * 0.05

        // 野兽数量加分（伙伴猎核心场面）
        val beastCount = me.playArea.cards.count { it.cardRace == CardRaceEnum.PET }
        score += (beastCount - 1).coerceIn(-1, 3) * 0.06

        // 法力充足
        if (me.usableResource >= 5) score += 0.1

        return score.coerceIn(0.0, 1.0)
    }

    override fun reset() {
        super.reset()
    }
}
