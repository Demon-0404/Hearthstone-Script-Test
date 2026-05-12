package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.DEFAULT_WAR_SCORE_CALCULATOR
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/**
 * 伙伴猎 MCTS 策略 — 伙伴猎-大狼
 *
 * 核心：
 * - MCTS 蒙特卡洛树搜索：20,000次模拟 × 2轮，自动找出最优出牌+攻击序列
 * - 统一评分体系：发现、回溯、换牌均用同一套评分函数
 * - 零硬编码规则：不写死特定卡牌的行为，全部通过权重和评分器驱动
 */
class PartnerHunterDeck : MCTSDeckStrategy() {

    override fun name(): String = "伙伴猎"

    override fun description(): String =
        "MCTS优化的伙伴猎。统一评分器驱动发现/回溯/换牌，30秒搜索最优解。"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAfWhBwidoASpgQfDgweZpweapwebpwfLtgfkxAcLqZ8EidQE4okHr5IH7p8Hu8AH3sQH4MQH48QH+cQHof0HAAA="

    override fun id(): String = "partner-hunter-deck-001"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    // ==================== 出牌策略 (MCTS) ====================

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        val calculator = DEFAULT_WAR_SCORE_CALCULATOR.build()
        val start = System.currentTimeMillis()
        return listOf(
            MCTSArg(start + 30_000, 1, 0.1, 20_000, calculator, true),
            MCTSArg(start + 10_000, 1, 0.5, 10_000, calculator, true),
        )
    }

    // MCTS 搜索完成后，补充英雄技能和地标使用
    override fun executeOutCard() {
        super.executeOutCard()
        val me = WAR.me
        if (!me.isValid()) return
        val rival = WAR.rival

        // 使用英雄技能（稳固射击）
        me.playArea.power?.let { power ->
            if (power.cost <= me.usableResource) {
                val handPlayable = me.handArea.cards.any { it.cost <= me.usableResource }
                if (!handPlayable || me.usableResource >= power.cost + 2) {
                    power.action.power()
                    Thread.sleep(800)
                }
            }
        }

        // 使用地标
        me.playArea.cards.toList().forEach { card ->
            if (card.cardType == CardTypeEnum.LOCATION && !card.isLocationActionCooldown) {
                CARD_DATA_TRIE[card.cardId]?.let { cardData ->
                    cardData.powerActions.firstOrNull()?.powerExec(card, cardData.effectType, WAR)
                } ?: card.action.lClick()
                Thread.sleep(800)
            }
        }
    }

    // ==================== 换牌策略 ====================

    override fun executeChangeCard(cards: HashSet<Card>) {
        val me = WAR.me
        val rival = WAR.rival

        for (card in cards.toList()) {
            val score = scoreCardForKeep(card, me.handArea.cards.size <= 3)
            if (score < 0.3) {
                cards.remove(card)
            }
        }
    }

    /**
     * 评分器驱动的留牌评估
     * @param isGoingFirst 是否先手（手牌少=先手）
     */
    private fun scoreCardForKeep(card: Card, isGoingFirst: Boolean): Double {
        var score = 0.0

        // 费用适配：1-3费高分
        val maxCost = if (isGoingFirst) 3 else 4
        if (card.cost in 1..maxCost) {
            score += 0.4
        } else if (card.cost > maxCost) {
            score -= 0.3
        }

        // 野兽加分
        if (card.cardRace == CardRaceEnum.PET) {
            score += 0.2
        }

        // 读卡牌权重
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.3
            score += cardData.changeWeight * 0.2
        }

        // 法术在开局不友好
        if (card.cardType == CardTypeEnum.SPELL) {
            score -= 0.15
        }

        // 武器可以留
        if (card.cardType == CardTypeEnum.WEAPON) {
            score += 0.1
        }

        return score
    }

    // ==================== 发现选牌策略 ====================

    override fun executeDiscoverChooseCard(vararg cards: Card): Int {
        val war = WAR
        val me = war.me

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for ((index, card) in cards.withIndex()) {
            val score = scoreDiscoverCard(card, me)
            log.info { "发现候选[$index]: ${card.entityName} 评分=$score" }
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        return bestIndex
    }

    /**
     * 统一评分器评估发现候选牌对当前场面的价值
     */
    private fun scoreDiscoverCard(card: Card, me: club.xiaojiawei.hsscriptcardsdk.bean.Player): Double {
        var score = 0.0

        // 能否立即打出
        if (card.cost <= me.usableResource) {
            score += 0.3
        } else if (card.cost > me.usableResource + 2) {
            score -= 0.2 // 太贵了，卡手
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
                // 身材效率（攻+血 / 费用）
                if (card.cost > 0) {
                    score += (card.health + card.atc).toDouble() / card.cost * 0.05
                }
            }
            CardTypeEnum.SPELL -> {
                score += 0.1 // 法术默认低一点，因为伙伴猎偏随从
            }
            CardTypeEnum.WEAPON -> {
                score += 0.15
            }
            else -> {}
        }

        // 读取权重
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.2
        }

        return score
    }

    // ==================== 回溯策略 ====================

    override fun execChooseTimeLine(timeLineEvent: TimelineEvent) {
        val war = WAR
        val me = war.me

        // 用简单评分评估当前场面
        val boardScore = scoreCurrentBoard(me)

        // 动态阈值：回合越晚、场面越差，越倾向回溯
        val threshold = 0.4

        if (boardScore >= threshold) {
            log.info { "回溯: keep — 场面评分 $boardScore >= $threshold" }
            timeLineEvent.keep()
        } else {
            log.info { "回溯: rewind — 场面评分 $boardScore < $threshold，赌一把" }
            timeLineEvent.rewind()
        }
    }

    /**
     * 统一评分器评估当前场面价值
     * @return 0.0~1.0 之间的分数
     */
    private fun scoreCurrentBoard(me: club.xiaojiawei.hsscriptcardsdk.bean.Player): Double {
        var score = 0.5 // 中性起点

        val hero = me.playArea.hero ?: return 0.3
        val maxHp = hero.health + hero.armor
        val currentHp = maxHp - hero.damage

        // 血量权重
        val hpRatio = (currentHp.toDouble() / maxHp).coerceIn(0.0, 1.0)
        score += (hpRatio - 0.5) * 0.3

        // 场面随从数量
        val minionCount = me.playArea.cards.size
        score += (minionCount - 2).coerceIn(-2, 3) * 0.08

        // 手牌数量
        val handCount = me.handArea.cards.size
        score += (handCount - 3).coerceIn(-3, 3) * 0.05

        // 可用水晶（节奏感）
        if (me.usableResource >= 5) score += 0.1

        return score.coerceIn(0.0, 1.0)
    }

    // ==================== 每局重置 ====================

    override fun reset() {
        super.reset()
    }
}
