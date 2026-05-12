package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy

/**
 * 伙伴猎卡组策略
 *
 * 核心思路：
 * 1. 优先铺场野兽/伙伴体系随从，按费拍怪
 * 2. 英雄技能（稳固射击）在有多余水晶时打脸
 * 3. 随从交换优先解嘲讽，其余走脸
 * 4. 换牌保留低费随从和野兽
 */
class PartnerHunterDeck : DeckStrategy() {

    override fun name(): String = "伙伴猎"

    override fun description(): String =
        "围绕伙伴体系和野兽随从的快攻猎策略。优先按费拍怪、用技能打脸、随从走脸。"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "partner-hunter-deck-001"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    // ==================== 换牌策略 ====================

    override fun executeChangeCard(cards: HashSet<Card>) {
        val me = WAR.me
        // 先手留1-3费，后手可以留到4费
        val maxKeepCost = if (me.let { it.handArea.cards.size <= 3 }) 3 else 4

        for (card in cards.toList()) {
            // 保留低费野兽随从
            if (card.cardType == CardTypeEnum.MINION &&
                card.cost in 1..maxKeepCost &&
                (card.cardRace == CardRaceEnum.PET || card.cardRace == null)
            ) {
                continue
            }
            // 保留低费优质武器（如弓）
            if (card.cardType == CardTypeEnum.WEAPON && card.cost <= 3) {
                continue
            }
            // 其他情况换掉
            cards.remove(card)
        }
    }

    // ==================== 出牌策略 ====================

    override fun executeOutCard() {
        val war = WAR
        val me = war.me
        if (!me.isValid()) return
        val rival = war.rival
        if (!rival.isValid()) return

        val handCards = me.handArea.cards.toMutableList()
        val playCards = me.playArea.cards.toMutableList()
        val hero = me.playArea.hero
        val weapon = me.playArea.weapon
        val heroPower = me.playArea.power
        val usableResource = me.usableResource
        val rivalHero = rival.playArea.hero

        // 移除硬币牌，单独处理
        handCards.removeAll { it.isCoinCard }

        // --- 第1步：使用英雄技能 ---
        // 猎人的稳固射击：有多余2费且没有更优动作时打脸
        heroPower?.let { power ->
            if (usableResource >= (power.cost + 1) || // 有富余水晶
                (handCards.isEmpty() && usableResource >= power.cost) // 没手牌了
            ) {
                if (power.cost <= usableResource) {
                    log.info { "使用英雄技能打脸" }
                    power.action.power()
                    Thread.sleep(800)
                }
            }
        }

        // --- 第2步：使用武器攻击 ---
        weapon?.let { w ->
            if (w.canAttack()) {
                // 优先解嘲讽随从
                val tauntTarget = rival.playArea.cards.firstOrNull { it.isTaunt && it.canBeAttacked() }
                if (tauntTarget != null) {
                    log.info { "武器攻击嘲讽随从: ${tauntTarget.entityName}" }
                    w.action.attack(tauntTarget)
                    Thread.sleep(800)
                } else if (rivalHero != null) {
                    log.info { "武器攻击敌方英雄" }
                    w.action.attackHero()
                    Thread.sleep(800)
                }
            }
        }

        // --- 第3步：出牌 ---
        val remainingResource = me.usableResource
        // 按费用从低到高排序
        val sortedHand = handCards
            .filter { it.cost <= remainingResource }
            .sortedBy { it.cost }

        for (card in sortedHand) {
            if (me.usableResource < card.cost) continue
            if (card.cardType == CardTypeEnum.MINION && me.playArea.isFull) break

            when (card.cardType) {
                CardTypeEnum.MINION -> {
                    val cardData = CARD_DATA_TRIE[card.cardId]
                    if (cardData != null && cardData.powerActions.isNotEmpty()) {
                        // 有预设行为：使用 autoPower
                        card.action.autoPower(cardData)
                    } else {
                        // 无预设行为：直接打出
                        if (card.isBattlecry) {
                            // 战吼牌尝试指向敌方英雄（如果战吼目标允许）
                            card.action.power()?.pointTo(rivalHero!!)
                        } else {
                            card.action.power()
                        }
                    }
                    log.info { "打出随从: ${card.entityName} (${card.cost}费)" }
                    Thread.sleep(800)
                }

                CardTypeEnum.SPELL -> {
                    val cardData = CARD_DATA_TRIE[card.cardId]
                    if (cardData != null) {
                        card.action.autoPower(cardData)
                    } else {
                        card.action.power()
                    }
                    log.info { "使用法术: ${card.entityName}" }
                    Thread.sleep(800)
                }

                CardTypeEnum.WEAPON -> {
                    card.action.power()
                    log.info { "装备武器: ${card.entityName}" }
                    Thread.sleep(800)
                }

                CardTypeEnum.HERO -> {
                    card.action.power()
                    log.info { "变身英雄: ${card.entityName}" }
                    Thread.sleep(1500)
                }

                CardTypeEnum.LOCATION -> {
                    card.action.power()
                    log.info { "使用地标: ${card.entityName}" }
                    Thread.sleep(800)
                }

                else -> {}
            }
        }

        // --- 第4步：随从攻击 ---
        attackWithMinions(me, rival)

        // --- 第5步：地标技能 ---
        me.playArea.cards.toList().forEach { card ->
            if (card.cardType == CardTypeEnum.LOCATION && !card.isLocationActionCooldown) {
                CARD_DATA_TRIE[card.cardId]?.let { cardData ->
                    cardData.powerActions.firstOrNull()?.powerExec(card, cardData.effectType, WAR)
                } ?: card.action.lClick()
                Thread.sleep(800)
            }
        }
    }

    /**
     * 随从攻击逻辑：
     * - 随从交换：用攻击力合适的随从清理敌方随从
     * - 走脸：没有嘲讽阻挡时攻击敌方英雄
     */
    private fun attackWithMinions(me: club.xiaojiawei.hsscriptcardsdk.bean.Player,
                                   rival: club.xiaojiawei.hsscriptcardsdk.bean.Player) {
        val myMinions = me.playArea.cards.toMutableList()
        val rivalMinions = rival.playArea.cards.toMutableList()
        val rivalHero = rival.playArea.hero

        // 第一步：处理必须解的敌方随从
        for (myMinion in myMinions.toList()) {
            if (!myMinion.canAttack()) continue
            if (!myMinions.contains(myMinion)) continue // 可能在攻击过程中被移除

            for (rivalMinion in rivalMinions.toList()) {
                if (!rivalMinion.canBeAttacked()) continue

                val shouldTrade = rivalMinion.isTaunt ||
                        rivalMinion.atc >= 5 || // 高攻威胁
                        (rivalMinion.cardRace == CardRaceEnum.PET && rivalMinion.atc >= 3)

                if (shouldTrade) {
                    // 换掉：用我方能换掉的最小攻击力随从
                    if (myMinion.atc >= rivalMinion.health + rivalMinion.armor - rivalMinion.damage ||
                        myMinion.isPoisonous
                    ) {
                        log.info { "${myMinion.entityName} 攻击 ${rivalMinion.entityName}" }
                        myMinion.action.attack(rivalMinion)
                        Thread.sleep(800)
                        myMinions.remove(myMinion)
                        rivalMinions.remove(rivalMinion)
                        break
                    }
                }
            }
        }

        // 第二步：剩余能攻击的随从走脸
        for (myMinion in myMinions.toList()) {
            if (!myMinion.canAttack()) continue
            if (myMinion.isAttackableByRush) continue // 突袭本回合不能打英雄

            // 检查是否还有嘲讽阻挡
            val hasTaunt = rival.playArea.cards.any { it.isTaunt && it.canBeAttacked() }
            if (hasTaunt) break

            if (rivalHero != null) {
                log.info { "${myMinion.entityName} 攻击敌方英雄" }
                myMinion.action.attackHero()
                Thread.sleep(800)
            }
        }

        // 第三步：处理剩余的嘲讽（如果还有）
        for (myMinion in me.playArea.cards.toList()) {
            if (!myMinion.canAttack()) continue
            val tauntMinion = rival.playArea.cards.firstOrNull { it.isTaunt && it.canBeAttacked() }
            if (tauntMinion != null) {
                log.info { "${myMinion.entityName} 攻击嘲讽 ${tauntMinion.entityName}" }
                myMinion.action.attack(tauntMinion)
                Thread.sleep(800)
            }
        }
    }

    // ==================== 发现选牌策略 ====================

    override fun executeDiscoverChooseCard(vararg cards: Card): Int {
        // 优先选择野兽随从
        for ((index, card) in cards.withIndex()) {
            if (card.cardRace == CardRaceEnum.PET && card.cardType == CardTypeEnum.MINION) {
                return index
            }
        }
        // 其次选择低费随从
        var minCostIndex = 0
        var minCost = Int.MAX_VALUE
        for ((index, card) in cards.withIndex()) {
            if (card.cardType == CardTypeEnum.MINION && card.cost < minCost) {
                minCost = card.cost
                minCostIndex = index
            }
        }
        // 如果都是法术/武器，选费用最低的
        if (minCost == Int.MAX_VALUE) {
            for ((index, card) in cards.withIndex()) {
                if (card.cost < minCost) {
                    minCost = card.cost
                    minCostIndex = index
                }
            }
        }
        return minCostIndex
    }
}
