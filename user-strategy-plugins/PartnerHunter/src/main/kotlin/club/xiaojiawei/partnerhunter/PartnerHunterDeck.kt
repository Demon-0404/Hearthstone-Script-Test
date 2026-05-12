package club.xiaojiawei.partnerhunter

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbasestrategy.bean.SimulateWeightCard
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

// ==================== 已知卡牌覆盖（行为解析失败的卡牌） ====================

private data class KnownCardInfo(
    val cardType: CardTypeEnum? = null,
    val atc: Int = 0,
    val health: Int = 0,
    val cardRace: CardRaceEnum? = null,
    val isBattlecry: Boolean = false,
    val isTaunt: Boolean = false,
    val isRush: Boolean = false,
    val isCharge: Boolean = false,
    val isPoisonous: Boolean = false,
    val isLifesteal: Boolean = false,
    val isDivineShield: Boolean = false,
    val isReborn: Boolean = false,
    val bonus: Double = 0.0,
    val isUpgradeCompanion: Boolean = false,  // 升级动物伙伴的卡
    val isSecret: Boolean = false,             // 奥秘
    val dynamicCostByEnemy: Boolean = false,   // 费用随敌方随从数减少
    val aoeDamage: Int = 0,                    // 战吼/法术对敌方全体造成伤害
    val isHeal: Boolean = false,               // 治疗牌
    val isFlip: Boolean = false,               // 炼金师翻转效果
)

private val KNOWN_CARD_MAP: Map<String, KnownCardInfo> = mapOf(
    // --- 核心升级牌 ---
    "MEND_307" to KnownCardInfo(  // 自由漫步 7费
        cardType = CardTypeEnum.SPELL, bonus = 8.0, isUpgradeCompanion = true),
    "MEND_304" to KnownCardInfo(  // 塔雅·陆行 5费4/4 野兽 战吼
        cardType = CardTypeEnum.MINION, atc = 4, health = 4,
        cardRace = CardRaceEnum.PET, isBattlecry = true, bonus = 10.0,
        isUpgradeCompanion = true),
    "MEND_303" to KnownCardInfo(  // 迁徙的雷象 3费3/4 野兽
        cardType = CardTypeEnum.MINION, atc = 3, health = 4,
        cardRace = CardRaceEnum.PET, bonus = 4.0),
    "MEND_301" to KnownCardInfo(  // 灵语猎手 4费2/2
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        cardRace = CardRaceEnum.PET, bonus = 2.0),
    "MEND_300" to KnownCardInfo(  // 驯服宠物 2费
        cardType = CardTypeEnum.SPELL, bonus = 4.0, isUpgradeCompanion = true),
    "MEND_305" to KnownCardInfo(  // 滋养自然 2费
        cardType = CardTypeEnum.SPELL, bonus = 1.0),
    // --- 战吼发现牌 ---
    "TIME_609" to KnownCardInfo(  // 游侠将军希尔瓦娜斯 3费(奇闻) AOE战吼
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, bonus = 2.0, aoeDamage = 2),
    "TIME_609t1" to KnownCardInfo(  // 游侠队长奥蕾莉亚 3费2/4 战吼
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, bonus = 2.0),
    "EDR_856" to KnownCardInfo(  // 梦魇之王萨维斯 4费4/4 战吼
        cardType = CardTypeEnum.MINION, atc = 4, health = 4,
        isBattlecry = true, bonus = 2.0),
    // --- 其他关键牌 ---
    "EDR_853" to KnownCardInfo(  // 布罗尔·熊皮 5费3/5 战吼
        cardType = CardTypeEnum.MINION, atc = 3, health = 5,
        isBattlecry = true, bonus = 2.0),
    "TIME_715" to KnownCardInfo(  // 为了荣耀！抽牌 动态费用
        cardType = CardTypeEnum.SPELL, bonus = 3.0, dynamicCostByEnemy = true),
    "DINO_434" to KnownCardInfo(  // 迅猛龙巢护工 1费2/3 野兽
        cardType = CardTypeEnum.MINION, atc = 2, health = 3,
        cardRace = CardRaceEnum.PET, bonus = 1.0),
    "TLC_463" to KnownCardInfo(  // 雷兹迪尔 7费7/7
        cardType = CardTypeEnum.MINION, atc = 7, health = 7, bonus = 1.0),
    "EDR_251" to KnownCardInfo(  // 龙鳞军备 1费
        cardType = CardTypeEnum.SPELL, bonus = 1.0),
    "CORE_BAR_801" to KnownCardInfo(  // 击伤猎物 2费
        cardType = CardTypeEnum.SPELL, bonus = 0.5),
    "TLC_823" to KnownCardInfo(  // 恐惧畏缩 2费
        cardType = CardTypeEnum.SPELL, bonus = 0.5),
    "FIR_954" to KnownCardInfo(  // 焚烧 1费
        cardType = CardTypeEnum.SPELL, bonus = 0.5),
    "TIME_600" to KnownCardInfo(  // 精确射击 2费
        cardType = CardTypeEnum.SPELL, bonus = 0.5),
    "DINO_403" to KnownCardInfo(  // 魔暴龙面具 8费
        cardType = CardTypeEnum.SPELL, bonus = 0.0),
    "EDR_482" to KnownCardInfo(  // 烂苹果 2费 回12血
        cardType = CardTypeEnum.SPELL, bonus = 0.0, isHeal = true),
    "CORE_EX1_059" to KnownCardInfo(  // 疯狂的炼金师 2费2/2 战吼翻转
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        isBattlecry = true, isFlip = true, bonus = 0.5),
    "RLK_503" to KnownCardInfo(  // 扛包收尸人 1费1/2 战吼
        cardType = CardTypeEnum.MINION, atc = 1, health = 2,
        isBattlecry = true, bonus = 0.5),
)

class PartnerHunterDeck : DeckStrategy() {

    override fun name(): String = "伙伴猎-v3"

    override fun description(): String =
        "伙伴猎v3：身材效率DP+贪婪填充+主动解场+升级牌优先+发现评分+威胁评估"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAfWhBwidoASpgQfDgweZpweapwebpwfLtgfkxAcLqZ8EidQE4okHr5IH7p8Hu8AH3sQH4MQH48QH+cQHof0HAAA="

    override fun id(): String = "partner-hunter-deck-v3"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    // ==================== 换牌策略 ====================

    override fun executeChangeCard(cards: HashSet<Card>) {
        val me = WAR.me
        val isGoingFirst = me.handArea.cards.size <= 3
        for (card in cards.toList()) {
            if (scoreCardForKeep(card, isGoingFirst) < 0.4) {
                cards.remove(card)
            }
        }
    }

    private fun scoreCardForKeep(card: Card, isGoingFirst: Boolean): Double {
        var score = 0.0
        val maxCost = if (isGoingFirst) 3 else 4
        if (card.cost == 1) score += 0.5
        if (card.cost in 2..maxCost) score += 0.3
        else if (card.cost > maxCost) score -= 0.4
        if (card.cardRace == CardRaceEnum.PET) score += 0.25
        if (card.isBattlecry) score += 0.2
        if (card.cardType == CardTypeEnum.SPELL && card.cost <= 2) score += 0.15
        if (card.cardType == CardTypeEnum.SPELL && card.cost >= 5) score -= 0.35
        if (card.cardType == CardTypeEnum.WEAPON) score += 0.1
        if (card.cost > 0) {
            score += (card.health + card.atc).toDouble() / card.cost * 0.08
        }
        if (card.cost in 1..3 && card.isBattlecry && card.atc <= 1) score += 0.15
        val known = KNOWN_CARD_MAP[card.cardId]
        if (known != null) {
            score += known.bonus * 0.15
            if (known.isUpgradeCompanion) score += 0.3
        }
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.15
            score += cardData.changeWeight * 0.15
        }
        return score
    }

    // ==================== 出牌策略 ====================

    override fun executeOutCard() {
        try {
            executeOutCardInternal()
        } catch (e: Throwable) {
            log.error(e) { "executeOutCard 异常" }
        }
    }

    private fun executeOutCardInternal() {
        val me = WAR.me
        if (!me.isValid()) return
        val rival = WAR.rival
        if (!rival.isValid()) return

        val heroPower = me.playArea.power
        var plays = me.playArea.cards.toList()

        // 1. 地标
        DeckStrategyUtil.activeLocation(plays)

        // 2. 敌方评估
        val enemyMinions = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val enemyAtk = enemyMinions.sumOf { it.atc }
        val hasBig = enemyMinions.any { it.atc >= 4 }
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        log.info { "=== ${me.usableResource}费 手牌${me.handArea.cards.size} 敌${enemyMinions.size}个(攻${enemyAtk}) ===" }

        // 2.5 场面空间预清（满场或接近满场时先送小怪）
        if (myMinionCount >= 5) {
            preClearForSpace(me, enemyMinions)
        }

        // 3. 手牌
        val hands = me.handArea.cards.toList()
        val myCards = hands.toMutableList()
        myCards.removeAll { it.isCoinCard }

        // 检测手牌中是否有升级牌/奥秘
        val hasUpgradeInHand = myCards.any { c ->
            val known = KNOWN_CARD_MAP[c.cardId]
            known?.isUpgradeCompanion == true ||
                (c.cost in 1..3 && c.isBattlecry && c.atc <= 1)
        }
        val hasBearskinInHand = myCards.any { it.cardId == "EDR_853" }

        // 4. 自定义DP
        val (dpScore, dpCards) = customDP(myCards, me.usableResource, enemyMinions, hasBig,
            hasUpgradeInHand, hasBearskinInHand)
        val dpFmt = "%.1f".format(dpScore)
        log.info { "DP得分${dpFmt} 选中${dpCards.size}张" }

        var finalCards = dpCards

        // 5. 硬币
        val coin = DeckStrategyUtil.findCoin(hands)
        if (coin != null) {
            val (cScore, cCards) = customDP(myCards, me.usableResource + 1, enemyMinions, hasBig,
                hasUpgradeInHand, hasBearskinInHand)
            if (cScore > dpScore + 1.5) {
                val cFmt = "%.1f".format(cScore)
                log.info { "硬币 得分${cFmt}" }
                coin.action.power()
                Thread.sleep(1000)
                finalCards = cCards
            }
        }

        // 6. 排序出牌
        if (finalCards.isNotEmpty()) {
            DeckStrategyUtil.updateTextForCard(finalCards)
            val sorted = sortCards(finalCards)
            log.info { "出牌序列:" }
            for (swc in sorted) {
                val v = "%.1f".format(swc.weight)
                log.info { "  ${swc.card.entityName}(${swc.card.cost}费)[v=${v}]" }
            }
            var used = 0
            for (swc in sorted) {
                val c = swc.card
                if (me.usableResource >= c.actualCost(me, enemyMinions)) {
                    if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                        if (!me.playArea.isFull) {
                            c.action.autoPower(CARD_DATA_TRIE[c.cardId])
                        }
                    } else {
                        if (me.playArea.isFull) break
                        c.action.autoPower(CARD_DATA_TRIE[c.cardId])
                    }
                    used += c.actualCost(me, enemyMinions)
                    Thread.sleep(500)
                }
            }
            log.info { "DP消耗${used}费 剩${me.usableResource}费" }
        } else {
            log.info { "DP未选中牌" }
        }

        // 7. 主动解场（斩杀回合跳过）
        val hasLethal = checkLethal(me, rival)
        if (!hasLethal) {
            activeClear()
        } else {
            log.info { "检测到可斩杀，跳过主动解场" }
        }

        // 8. cleanPlay收尾
        DeckStrategyUtil.cleanPlay()

        // 9. 贪婪填充（按优先级排序）
        val remaining = me.handArea.cards.toList()
            .filter { !it.isCoinCard && it.actualCost(me, enemyMinions) <= me.usableResource }
            .sortedByDescending { calcValue(it, me.usableResource, enemyMinions, hasBig, hasUpgradeInHand, hasBearskinInHand) }
        if (remaining.isNotEmpty() && me.usableResource > 0) {
            log.info { "贪婪填充: 剩${me.usableResource}费 ${remaining.size}张" }
            for (c in remaining) {
                val actualCost = c.actualCost(me, enemyMinions)
                if (me.usableResource >= actualCost) {
                    if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                        c.action.autoPower(CARD_DATA_TRIE[c.cardId])
                    } else if (!me.playArea.isFull) {
                        // 满场前再预清一次
                        if (me.playArea.cards.count { it.cardType == CardTypeEnum.MINION } >= 6) {
                            preClearForSpace(me, enemyMinions)
                        }
                        if (!me.playArea.isFull) {
                            c.action.autoPower(CARD_DATA_TRIE[c.cardId])
                        }
                    }
                    Thread.sleep(400)
                }
            }
        }

        // 10. 地标二次
        plays = me.playArea.cards.toList()
        DeckStrategyUtil.activeLocation(plays)

        // 11. 英雄技能
        heroPower?.let { p ->
            if (me.usableResource >= p.cost) {
                val has = me.handArea.cards.any {
                    val ac = it.actualCost(me, enemyMinions)
                    ac <= me.usableResource &&
                        (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.SPELL)
                }
                if (!has || me.usableResource >= p.cost + 3) {
                    log.info { "英雄技能" }
                    p.action.power()
                    Thread.sleep(800)
                }
            }
        }

        // 12. 激发
        me.playArea.cards.toList().forEach { c ->
            if (c.isLaunchpad && me.usableResource >= c.launchCost()) {
                c.action.launch()
                Thread.sleep(800)
            }
        }
    }

    // ==================== 斩杀检测 ====================

    private fun checkLethal(me: Player, rival: Player): Boolean {
        val myAtk = me.playArea.cards
            .filter { it.atc > 0 && (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.HERO) }
            .sumOf { it.atc }
        val hero = rival.playArea.hero ?: return false
        val rivalHp = hero.health + hero.armor - hero.damage
        val hasTaunt = rival.playArea.cards.any { it.isTaunt && it.cardType == CardTypeEnum.MINION }
        return myAtk >= rivalHp && !hasTaunt
    }

    // ==================== 场面预清 ====================

    private fun preClearForSpace(me: Player, enemyMinions: List<Card>) {
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        if (myMinions.size < 2 || enemyMinions.isEmpty()) return

        // 找最小代价交换：用小身材换大威胁
        for (enemy in enemyMinions.sortedByDescending { it.atc }) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            if (attackers.isEmpty()) break

            // 用小怪换大怪
            val small = attackers
                .filter { it.atc <= 2 && it.health <= 3 }
                .minByOrNull { it.atc.toDouble() * it.health.toDouble() }
            if (small != null && (enemy.atc >= 3 || enemy.isTaunt)) {
                log.info { "预清空间: ${small.entityName}(${small.atc}/${small.health})→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                small.action.attack(enemy)
                Thread.sleep(400)
                continue
            }
            // 如果没有小怪但有能优势交换的
            val favorable = attackers.minByOrNull { it.atc.toDouble() * it.health.toDouble() }
            if (favorable != null && favorable.atc >= enemy.health && enemy.atc >= 3) {
                log.info { "预清空间: ${favorable.entityName}(${favorable.atc}/${favorable.health})→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                favorable.action.attack(enemy)
                Thread.sleep(400)
            }
        }
    }

    // ==================== 自定义DP ====================

    private fun customDP(
        cards: List<Card>,
        mana: Int,
        enemies: List<Card>,
        hasBig: Boolean,
        hasUpgradeInHand: Boolean = false,
        hasBearskinInHand: Boolean = false,
    ): Pair<Double, List<SimulateWeightCard>> {
        if (cards.isEmpty() || mana <= 0) return Pair(0.0, emptyList())
        val me = WAR.me
        val n = cards.size
        val vals = DoubleArray(n) { i ->
            calcValue(cards[i], mana, enemies, hasBig, hasUpgradeInHand, hasBearskinInHand)
        }
        val costs = IntArray(n) { i -> cards[i].actualCost(me, enemies) }
        val dp = DoubleArray(mana + 1)
        val keep = Array(n) { BooleanArray(mana + 1) }
        for (i in 0 until n) {
            val c = costs[i]
            if (c > mana) continue
            for (j in mana downTo c) {
                val nv = dp[j - c] + vals[i]
                if (nv > dp[j]) {
                    dp[j] = nv
                    keep[i][j] = true
                }
            }
        }
        val sel = mutableListOf<SimulateWeightCard>()
        var j = mana
        for (i in n - 1 downTo 0) {
            if (keep[i][j]) {
                sel.add(SimulateWeightCard(cards[i], vals[i], 0.0))
                j -= costs[i]
            }
        }
        return Pair(dp[mana], sel.reversed())
    }

    // ==================== 卡牌价值 ====================

    private fun calcValue(
        c: Card,
        mana: Int,
        enemies: List<Card>,
        hasBig: Boolean,
        hasUpgradeInHand: Boolean = false,
        hasBearskinInHand: Boolean = false,
    ): Double {
        val known = KNOWN_CARD_MAP[c.cardId]
        var effectiveType = c.cardType
        var effectiveAtc = c.atc
        var effectiveHealth = c.health
        var effectiveRace = c.cardRace
        var effectiveBattlecry = c.isBattlecry
        var effectiveTaunt = c.isTaunt
        var effectiveRush = c.isRush
        var effectiveCharge = c.isCharge
        var effectivePoisonous = c.isPoisonous
        var effectiveLifesteal = c.isLifesteal
        var effectiveDivineShield = c.isDivineShield
        var effectiveReborn = c.isReborn

        // 用已知卡牌信息覆盖 INVALID 属性
        if (c.cardType == CardTypeEnum.INVALID && known != null) {
            known.cardType?.let { effectiveType = it }
            if (known.atc > 0) effectiveAtc = known.atc
            if (known.health > 0) effectiveHealth = known.health
            known.cardRace?.let { effectiveRace = it }
            if (known.isBattlecry) effectiveBattlecry = true
            if (known.isTaunt) effectiveTaunt = true
            if (known.isRush) effectiveRush = true
            if (known.isCharge) effectiveCharge = true
            if (known.isPoisonous) effectivePoisonous = true
            if (known.isLifesteal) effectiveLifesteal = true
            if (known.isDivineShield) effectiveDivineShield = true
            if (known.isReborn) effectiveReborn = true
        }

        var v = 0.5

        // 已知卡牌额外加成
        if (known != null) {
            v += known.bonus
        }

        // 身材效率
        if (c.cost > 0 && effectiveType == CardTypeEnum.MINION) {
            v += (effectiveAtc + effectiveHealth).toDouble() / c.cost * 0.6
        }

        // 升级牌（极大提高优先级）
        val isUpgradeByProps = c.cost in 1..3 && effectiveBattlecry && effectiveAtc <= 1
        val isUpgradeByMap = known?.isUpgradeCompanion == true
        if (isUpgradeByProps || isUpgradeByMap) {
            v += 8.0
        }

        // 双倍战吼
        if (c.cost in 4..5 && effectiveBattlecry) v += 3.0

        // 野兽
        if (effectiveRace == CardRaceEnum.PET && effectiveType == CardTypeEnum.MINION) v += 2.5

        // 关键词
        if (effectiveTaunt) { v += 1.5; if (hasBig) v += 1.5 }
        if (effectiveRush || effectiveCharge) { v += 2.5; if (enemies.isNotEmpty()) v += 1.5 }
        if (effectivePoisonous) v += 2.5
        if (effectiveLifesteal) { v += 1.5; if (hasBig) v += 1.0 }
        if (effectiveDivineShield) v += 1.0
        if (effectiveReborn) v += 0.8

        // 法术
        if (effectiveType == CardTypeEnum.SPELL) {
            if (c.cost <= 2) v += 1.5
            if (enemies.isNotEmpty() && c.cost <= 3) v += 2.0
            // 奥秘惩罚：手中有升级牌时，不急着挂奥秘
            if (known?.isSecret == true && hasUpgradeInHand) v -= 3.0
            // 熊皮协同：保留低费法术
            if (hasBearskinInHand && c.cost <= 2 && known?.isSecret != true) {
                v += 1.0
            }
        }

        // 武器
        if (effectiveType == CardTypeEnum.WEAPON) {
            v += effectiveAtc * 0.8 + 1.0
            if (enemies.isNotEmpty()) v += 1.0
        }

        // 地标
        if (effectiveType == CardTypeEnum.LOCATION) v += 1.5

        // 费用适配（用实际费用）
        val actualCost = c.actualCost(WAR.me, enemies)
        if (actualCost > 0 && actualCost <= mana) {
            v += actualCost.toDouble() / mana.coerceAtLeast(1) * 1.0
        }

        // 动态费用卡牌额外适配:如果实际费用远小于牌面费用,说明当前时机好
        if (known?.dynamicCostByEnemy == true && actualCost < c.cost) {
            v += (c.cost - actualCost) * 0.8
        }

        // AOE伤害: 战吼/法术对敌方全体造成伤害
        if (known?.aoeDamage ?: 0 > 0 && enemies.isNotEmpty()) {
            v += known!!.aoeDamage * enemies.size * 0.6
        }

        // 炼金师翻转: 场上有高生命低攻击随从时加分
        if (known?.isFlip == true) {
            val myMinions = WAR.me.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
            val hasFlipTarget = myMinions.any { it.health >= 10 && it.atc <= 2 }
            val hasEnemyFlipTarget = enemies.any { it.atc >= 8 && it.health <= 2 }
            if (hasFlipTarget) v += 5.0     // 翻转己方高血怪→斩杀
            if (hasEnemyFlipTarget) v += 3.0 // 翻转敌方高攻怪→解场
        }

        // 满血治疗惩罚
        if (known?.isHeal == true) {
            val hero = WAR.me.playArea.hero
            if (hero != null) {
                val maxHp = hero.health + hero.armor
                val curHp = maxHp - hero.damage
                if (curHp >= maxHp - 3) v -= 4.0  // 满血或接近满血，治疗浪费
            }
        }

        return v
    }

    // ==================== 卡牌实际费用 ====================

    private fun Card.actualCost(me: Player, enemies: List<Card>): Int {
        val known = KNOWN_CARD_MAP[this.cardId]
        if (known?.dynamicCostByEnemy == true) {
            return (this.cost - enemies.size).coerceAtLeast(0)
        }
        return this.cost
    }

    // ==================== 主动解场 ====================

    private fun activeClear() {
        val me = WAR.me
        val rival = WAR.rival
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        val enemyMinions = rival.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION }
            .sortedByDescending { it.atc }
        if (myMinions.isEmpty() || enemyMinions.isEmpty()) return

        for (enemy in enemyMinions) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            if (attackers.isEmpty()) break

            var best: Card? = null
            var bestCost = Double.MAX_VALUE
            for (a in attackers) {
                var cost = a.atc.toDouble() * a.health.toDouble()
                if (a.isRush || a.isCharge) cost *= 0.2
                if (a.cardRace == CardRaceEnum.PET) cost *= 1.3
                if (a.isTaunt) cost *= 1.5
                if (cost < bestCost) { bestCost = cost; best = a }
            }
            val attacker = best ?: continue

            // 提高解场阈值：敌方随从攻击力≥3才主动解
            val should = when {
                attacker.atc >= 4 && enemy.atc <= 1 -> false
                attacker.atc >= 3 && attacker.health <= 2 && enemy.atc >= 4 -> true
                enemy.atc >= 3 -> true
                attacker.isRush || attacker.isCharge -> true
                attacker.atc <= 1 && attacker.health <= 2 && enemy.atc >= 3 -> true
                attacker.atc >= 3 && enemy.atc >= attacker.health -> false
                else -> false
            }
            if (should) {
                log.info { "主动解场: ${attacker.entityName}(${attacker.atc}/${attacker.health})→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                attacker.action.attack(enemy)
                Thread.sleep(500)
            }
        }
    }

    // ==================== 排序 ====================

    private fun sortCards(cards: List<SimulateWeightCard>): List<SimulateWeightCard> {
        return cards.sortedBy { swc ->
            val c = swc.card
            val known = KNOWN_CARD_MAP[c.cardId]
            when {
                c.cost == 0 -> 0
                known?.isUpgradeCompanion == true -> 5  // 升级牌最高优先
                c.cardType == CardTypeEnum.LOCATION -> 10
                c.cost in 1..3 && c.isBattlecry && c.atc <= 1 -> 15
                c.cost in 4..5 && c.isBattlecry -> 20
                c.cardRace == CardRaceEnum.PET && c.cardType == CardTypeEnum.MINION -> 25
                c.cardType == CardTypeEnum.WEAPON -> 40
                c.cardType == CardTypeEnum.MINION -> 50
                c.cardType == CardTypeEnum.SPELL -> 60
                else -> 100
            }
        }
    }

    // ==================== 发现选牌 ====================

    override fun executeDiscoverChooseCard(vararg cards: Card): Int {
        val me = WAR.me
        var bestI = 0
        var bestS = Double.NEGATIVE_INFINITY
        val parts = mutableListOf<String>()
        for ((i, c) in cards.withIndex()) {
            val s = scoreDiscover(c, me)
            val sf = "%.2f".format(s)
            parts.add("[${i}]${c.entityName}(${c.atc}/${c.health})${c.cost}费=${sf}")
            if (s > bestS) { bestS = s; bestI = i }
        }
        log.info { "发现: ${parts.joinToString(" | ")} → 选${bestI}" }
        return bestI
    }

    private fun scoreDiscover(c: Card, me: Player): Double {
        val known = KNOWN_CARD_MAP[c.cardId]
        var s = 0.5

        // 用已知信息覆盖
        var effType = c.cardType
        var effAtc = c.atc
        var effHealth = c.health
        var effRace = c.cardRace
        var effBattlecry = c.isBattlecry
        if (c.cardType == CardTypeEnum.INVALID && known != null) {
            known.cardType?.let { effType = it }
            if (known.atc > 0) effAtc = known.atc
            if (known.health > 0) effHealth = known.health
            known.cardRace?.let { effRace = it }
            if (known.isBattlecry) effBattlecry = true
            s += known.bonus * 0.5
        }

        if (c.cost > 0 && effType == CardTypeEnum.MINION) {
            s += (effAtc + effHealth).toDouble() / c.cost * 0.7
        }
        if (c.cost <= me.usableResource) s += 0.35
        else if (c.cost > me.usableResource + 3) s -= 0.45
        if (effRace == CardRaceEnum.PET) s += 0.55
        if (effBattlecry) s += 0.25
        if (c.cost in 1..3 && c.atc <= 1 && effBattlecry) s += 0.5
        if (c.isTaunt) s += 0.35
        if (c.isPoisonous) s += 0.35
        if (c.isLifesteal) s += 0.3
        if (c.isRush || c.isCharge) { s += 0.45; if (effRace == CardRaceEnum.PET) s += 0.3 }
        if (c.isDivineShield) s += 0.2

        // 去除法术 vs 通用法术
        when (effType) {
            CardTypeEnum.SPELL -> {
                if (c.cost <= 2) s += 0.4
                else if (c.cost <= 4) s += 0.15
                // 去除类法术（低费、有敌方随从时）加分
                val enemyCount = WAR.rival.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                if (c.cost <= 3 && enemyCount > 0) s += 0.25
            }
            CardTypeEnum.WEAPON -> s += 0.3
            else -> {}
        }

        // 升级伙伴牌加分
        if (known?.isUpgradeCompanion == true) s += 0.6

        // AOE价值
        val enemyCount2 = WAR.rival.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        if ((known?.aoeDamage ?: 0) > 0 && enemyCount2 > 0) {
            s += known!!.aoeDamage * enemyCount2 * 0.3
        }

        // 翻转价值
        if (known?.isFlip == true) {
            val myMinions = me.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
            val hasFlipTarget = myMinions.any { it.health >= 10 && it.atc <= 2 }
            if (hasFlipTarget) s += 2.0
        }

        // 治疗牌惩罚
        if (known?.isHeal == true) {
            val hero = me.playArea.hero
            if (hero != null) {
                val maxHp = hero.health + hero.armor
                val curHp = maxHp - hero.damage
                if (curHp >= maxHp - 3) s -= 2.0
            }
        }

        CARD_DATA_TRIE[c.cardId]?.let { cd -> s += cd.weight * 0.1 }
        return s
    }

    // ==================== 回溯 ====================

    override fun execChooseTimeLine(tle: TimelineEvent) {
        // 伙伴猎降低回溯阈值：手牌无升级牌时更倾向回溯
        val me = WAR.me
        val hasUpgrade = me.handArea.cards.any { c ->
            val known = KNOWN_CARD_MAP[c.cardId]
            known?.isUpgradeCompanion == true ||
                (c.cost in 1..3 && c.isBattlecry && c.atc <= 1)
        }
        val threshold = if (hasUpgrade) 0.35 else 0.55
        if (scoreBoard(me) >= threshold) tle.keep() else tle.rewind()
    }

    private fun scoreBoard(me: Player): Double {
        var s = 0.5
        val hero = me.playArea.hero ?: return 0.3
        val maxHp = hero.health + hero.armor
        val curHp = maxHp - hero.damage
        val ratio = (curHp.toDouble() / maxHp).coerceIn(0.0, 1.0)
        s += (ratio - 0.5) * 0.4
        s += (me.playArea.cards.size - 2).coerceIn(-2, 3) * 0.08
        s += (me.handArea.cards.size - 3).coerceIn(-3, 3) * 0.05
        val beasts = me.playArea.cards.count { it.cardRace == CardRaceEnum.PET }
        s += (beasts - 1).coerceIn(-1, 3) * 0.06
        if (me.usableResource >= 5) s += 0.1
        val enemyCount = WAR.rival.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        s -= (enemyCount - 2).coerceAtLeast(0) * 0.06
        return s.coerceIn(0.0, 1.0)
    }

    override fun reset() {
        super.reset()
    }
}
