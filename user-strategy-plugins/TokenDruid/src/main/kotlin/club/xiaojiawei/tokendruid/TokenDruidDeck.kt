package club.xiaojiawei.tokendruid

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

// ==================== 已知卡牌覆盖 ====================

private data class KnownCardInfo(
    // 基础属性覆盖（INVALID卡牌用）
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
    val isElusive: Boolean = false,
    val bonus: Double = 0.0,
    // 铺场德专属
    val isRamp: Boolean = false,              // 跳费牌（激活、费伍德树人）
    val isTokenGenerator: Boolean = false,     // 生成随从token的牌
    val tokensGenerated: Int = 0,             // 生成的token数量
    val isBuff: Boolean = false,               // 群体buff牌
    val buffValue: Double = 0.0,              // buff估值（每随从）
    val isDraw: Boolean = false,               // 过牌
    val drawCount: Int = 0,                   // 过牌数量
    val needsSpace: Int = 0,                  // 需要空格子
    val needsTargeting: Boolean = false,       // 需要手动指向
    val targetsEnemy: Boolean = false,         // 指向敌方
    val isChooseOne: Boolean = false,          // 抉择牌
    val chooseOneIndex: Int = 0,               // 抉择默认选项
    val triggersTimeline: Boolean = false,     // 触发时间线选择
    val isLocation: Boolean = false,           // 地标
    val endOfTurnValue: Double = 0.0,          // 回合结束效果价值
)

private val KNOWN_CARD_MAP: Map<String, KnownCardInfo> = mapOf(
    // --- 跳费 ---
    "CORE_EX1_169" to KnownCardInfo(  // 激活 0费 获得1个临时法力水晶
        cardType = CardTypeEnum.SPELL, bonus = 3.0, isRamp = true),
    "CATA_131" to KnownCardInfo(  // 费伍德树人 2费2/2 战吼：获得临时水晶，耗4法力变永久
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        isBattlecry = true, isRamp = true, bonus = 3.0),
    // --- 地标 ---
    "FIR_907" to KnownCardInfo(  // 阿梅达希尔 5费0/3 地标
        cardType = CardTypeEnum.LOCATION, bonus = 4.0, isLocation = true),
    // --- 铺场引擎 ---
    "CORE_AT_037" to KnownCardInfo(  // 活体根须 1费 抉择：打2 或 召唤两个1/1树苗
        cardType = CardTypeEnum.SPELL, isChooseOne = true, chooseOneIndex = 1,
        isTokenGenerator = true, tokensGenerated = 2, bonus = 2.5),
    "TLC_232" to KnownCardInfo(  // 待哺群雏 2费 下回合开始召唤三只2/1啸天龙
        cardType = CardTypeEnum.SPELL, isTokenGenerator = true, tokensGenerated = 3, bonus = 2.5),
    "CATA_134" to KnownCardInfo(  // 荒林怪圈 4费 裂变：召唤两个2/2树人 + 亡语buff
        cardType = CardTypeEnum.SPELL, isTokenGenerator = true, tokensGenerated = 2,
        needsSpace = 2, bonus = 3.0),
    "CATA_210" to KnownCardInfo(  // 暮光龙卵 1费0/2 亡语：召唤2/2雏龙(每回合+1/+1)
        cardType = CardTypeEnum.MINION, atc = 0, health = 2,
        isTokenGenerator = true, tokensGenerated = 1, bonus = 2.0),
    "DINO_130" to KnownCardInfo(  // 长颈龙蛋 2费0/2 亡语：召唤3/3野兽 + 所有随从+1/+1
        cardType = CardTypeEnum.MINION, atc = 0, health = 2,
        isTokenGenerator = true, tokensGenerated = 1, isBuff = true, buffValue = 1.0, bonus = 2.5),
    // --- 群体buff ---
    "FIR_906" to KnownCardInfo(  // 过热 3费 随从+1/+1，弃自然法术再+1/+1
        cardType = CardTypeEnum.SPELL, isBuff = true, buffValue = 1.5, bonus = 1.5),
    "TLC_233" to KnownCardInfo(  // 孵化辅助师 3费2/3 战吼：≤2攻随从获+1/+2和嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 3,
        isBattlecry = true, isBuff = true, buffValue = 1.5, bonus = 2.5),
    "CATA_138" to KnownCardInfo(  // 森林赠礼 2费 你每有一个随从，随机友方+1/+1
        cardType = CardTypeEnum.SPELL, isBuff = true, buffValue = 1.0, bonus = 2.0),
    // --- 过牌/发现 ---
    "TLC_603" to KnownCardInfo(  // 栉龙 1费1/2 野兽 战吼抽1 亡语弃牌
        cardType = CardTypeEnum.MINION, atc = 1, health = 2,
        cardRace = CardRaceEnum.PET, isBattlecry = true,
        isDraw = true, drawCount = 1, bonus = 1.5),
    "TIME_701" to KnownCardInfo(  // 波涛形塑 1费 从牌库发现一张牌
        cardType = CardTypeEnum.SPELL, isDraw = true, drawCount = 1, bonus = 1.5),
    "EDR_270" to KnownCardInfo(  // 丰裕之角 2费 发现自然法术，减2费
        cardType = CardTypeEnum.SPELL, isDraw = true, drawCount = 1, bonus = 2.0),
    "DINO_432" to KnownCardInfo(  // 奔行豹面具 4费 变随从为5/4潜行，抽2
        cardType = CardTypeEnum.SPELL, needsTargeting = true, targetsEnemy = false,
        isDraw = true, drawCount = 2, bonus = 2.5),
    "EDR_856" to KnownCardInfo(  // 梦魇之王萨维斯 4费4/4 恶魔 战吼发现随从并给予黑暗之赐
        cardType = CardTypeEnum.MINION, atc = 4, health = 4,
        cardRace = CardRaceEnum.DEMON, isBattlecry = true,
        isDraw = true, drawCount = 1, bonus = 2.0),
    // --- 终端 ---
    "CATA_139" to KnownCardInfo(  // 柳牙 6费0/5 巨型+4
        cardType = CardTypeEnum.MINION, atc = 0, health = 5, bonus = 4.0),
    // --- 敌方嘲讽随从（常见铺场德对手的嘲讽）---
    "CORE_GVG_085" to KnownCardInfo(  // 吵吵机器人 2费1/2 圣盾嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 2,
        isTaunt = true, isDivineShield = true),
    "CORE_BOT_911" to KnownCardInfo(  // 青铜门卫 3费1/5 磁力嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 5, isTaunt = true),
    "CORE_OG_218" to KnownCardInfo(  // 血蹄勇士 4费2/6 嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 6, isTaunt = true),
    "CORE_TRL_401" to KnownCardInfo(  // 阿曼尼战熊 7费5/7 突袭嘲讽
        cardType = CardTypeEnum.MINION, atc = 5, health = 7,
        isTaunt = true, isRush = true),
    "CORE_EX1_048" to KnownCardInfo(  // 森金持盾卫士 4费3/5 嘲讽
        cardType = CardTypeEnum.MINION, atc = 3, health = 5, isTaunt = true),
    "CORE_ICC_807" to KnownCardInfo(  // 固守卫兵 1费1/3 嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 3, isTaunt = true),
)

// ==================== 铺场德-v1 策略主类 ====================

class TokenDruidDeck : DeckStrategy() {

    override fun name(): String = "铺场德-v1"

    override fun description(): String =
        "铺场德v1：跳费铺场+群体buff+地标协同+亡语赖场"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAfHGBwTDgwevhwe4nwfgwAcNrp8EgdQEiIMHrocHkpcHlJcH15cH2p0Hqq8H18AH28AH7MAH9sEHAAA="

    override fun id(): String = "token-druid-deck-v1"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    // ==================== 换牌策略 ====================

    override fun executeChangeCard(cards: HashSet<Card>) {
        val me = WAR.me
        val isGoingFirst = me.handArea.cards.size <= 3
        for (card in cards.toList()) {
            if (scoreCardForKeep(card, isGoingFirst) < 0.35) {
                cards.remove(card)
            }
        }
    }

    private fun scoreCardForKeep(card: Card, isGoingFirst: Boolean): Double {
        var score = 0.0
        val maxCost = if (isGoingFirst) 3 else 4
        val known = KNOWN_CARD_MAP[card.cardId]

        // 1费牌高优先级（先手1费有事做最重要）
        if (card.cost == 0) score += 0.6  // 激活完美开局
        if (card.cost == 1) {
            score += 0.5
            if (known?.isTokenGenerator == true) score += 0.2  // 1费铺场
            if (known?.isDraw == true) score += 0.1
        }
        if (card.cost in 2..maxCost) score += 0.3
        // 跳费牌: 在2-3费即可打出时加分
        if (known?.isRamp == true && card.cost <= maxCost) score += 0.4
        // 高费惩罚
        if (card.cost >= 6) score -= 2.5
        else if (card.cost > maxCost) score -= 0.4
        // 亡语蛋粘场加分
        if (card.cardId in setOf("CATA_210", "DINO_130")) score += 0.2
        // 1-2费随从加分
        if (card.cardType == CardTypeEnum.MINION && card.cost in 1..2) score += 0.15
        // 身材效率
        if (card.cost > 0 && card.cardType == CardTypeEnum.MINION) {
            score += (card.health + card.atc).toDouble() / card.cost * 0.06
        }
        // 已知牌bonus
        if (known != null && card.cost <= maxCost) {
            score += known.bonus * 0.1
        }
        CARD_DATA_TRIE[card.cardId]?.let { cardData ->
            score += cardData.weight * 0.1
            score += cardData.changeWeight * 0.1
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

        // 1. 地标激活
        DeckStrategyUtil.activeLocation(plays)

        // 2. 敌方场面评估
        val enemyMinions = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val enemyAtk = enemyMinions.sumOf { it.atc }
        val hasBig = enemyMinions.any { it.atc >= 4 }
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val freeSpace = 7 - myMinionCount
        log.info { "=== ${me.usableResource}费 手牌${me.handArea.cards.size} 我方${myMinionCount}随从(空${freeSpace}格) 敌${enemyMinions.size}个(攻${enemyAtk}) ===" }

        // 2.1 斩杀检测
        val hasLethal = checkLethal(me, rival)
        val nearLethal = isNearLethal(me, rival)
        if (hasLethal) log.info { "检测到可斩杀，跳过所有清场" }
        else if (nearLethal) log.info { "接近斩杀(场攻≥HP70%)，跳过小怪解场" }

        // 2.5 场面空间预清（非斩杀时，满场前清低价值随从）
        if (!hasLethal && !nearLethal && myMinionCount >= 5) {
            preClearForSpace(me, enemyMinions)
        }

        // 3. 手牌
        val hands = me.handArea.cards.toList()
        val myCards = hands.toMutableList()
        myCards.removeAll { it.isCoinCard }

        // 检测手牌类型分布（用于评分上下文）
        val hasBuffInHand = myCards.any { KNOWN_CARD_MAP[it.cardId]?.isBuff == true }
        val myTokenCount = me.playArea.cards.count {
            it.cardType == CardTypeEnum.MINION && it.atc <= 2
        }
        val hasTokensOnBoard = myTokenCount >= 2

        // 4. 自定义DP
        val (dpScore, dpCards) = customDP(myCards, me.usableResource, enemyMinions,
            myMinionCount, freeSpace, hasTokensOnBoard, hasBuffInHand)
        val dpFmt = "%.1f".format(dpScore)
        log.info { "DP得分${dpFmt} 选中${dpCards.size}张" }

        var finalCards = dpCards

        // 5. 硬币评估
        val coin = DeckStrategyUtil.findCoin(hands)
        if (coin != null) {
            val (cScore, cCards) = customDP(myCards, me.usableResource + 1, enemyMinions,
                myMinionCount, freeSpace, hasTokensOnBoard, hasBuffInHand)
            if (cScore > dpScore + 1.5) {
                val cFmt = "%.1f".format(cScore)
                log.info { "硬币 得分${cFmt}" }
                coin.action.power()
                Thread.sleep((100..180).random().toLong())
                finalCards = cCards
            }
        }

        // 6. 排序出牌
        if (finalCards.isNotEmpty()) {
            DeckStrategyUtil.updateTextForCard(finalCards)
            val sorted = sortCards(finalCards, myMinionCount, hasTokensOnBoard)
            log.info { "出牌序列:" }
            for (swc in sorted) {
                val v = "%.1f".format(swc.weight)
                log.info { "  ${swc.card.entityName}(${swc.card.cost}费)[v=${v}]" }
            }
            var used = 0
            var firstAction = true
            for (swc in sorted) {
                val c = swc.card
                if (me.usableResource >= c.actualCost(me, enemyMinions)) {
                    // 出牌前检查格子
                    val known = KNOWN_CARD_MAP[c.cardId]
                    val curMinionCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    val needSpace = known?.needsSpace
                        ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    if (curMinionCnt + needSpace > 7 && !hasLethal && !nearLethal) {
                        preClearForSpace(me, enemyMinions)
                    }
                    if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                        playCardWithTargeting(c, me, rival)
                    } else if (c.cardType === CardTypeEnum.LOCATION) {
                        c.action.power()
                    } else {
                        if (me.playArea.isFull) break
                        playCardWithTargeting(c, me, rival)
                    }
                    used += c.actualCost(me, enemyMinions)
                    Thread.sleep(if (firstAction) (100..180).random().toLong() else (80..150).random().toLong())
                    firstAction = false
                }
            }
            log.info { "DP消耗${used}费 剩${me.usableResource}费" }
        } else {
            log.info { "DP未选中牌" }
        }

        // 6.5 时间线检测
        val hasTimelineTrigger = finalCards.any { KNOWN_CARD_MAP[it.card.cardId]?.triggersTimeline == true }
        if (hasTimelineTrigger) {
            log.info { "已打出时间线触发牌，跳过解场/清理" }
            Thread.sleep((500..800).random().toLong())
            return
        }

        // 7. 主动解场（非斩杀时）
        if (!hasLethal) {
            if (nearLethal) {
                clearHighThreatsOnly()
            } else {
                activeClear()
            }
        }

        // 8. cleanPlay
        DeckStrategyUtil.cleanPlay()

        // 8.5 兜底攻击
        postCleanUpAttacks(me, rival)

        // 9. 贪婪填充
        val updatedFreeSpace = 7 - me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val hasTokensNow = me.playArea.cards.count {
            it.cardType == CardTypeEnum.MINION && it.atc <= 2
        } >= 2
        val remaining = me.handArea.cards.toList()
            .filter { !it.isCoinCard && it.actualCost(me, enemyMinions) <= me.usableResource }
            .sortedByDescending { calcValue(it, me.usableResource, enemyMinions, me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }, updatedFreeSpace, hasTokensNow, hasBuffInHand) }
        if (remaining.isNotEmpty() && me.usableResource > 0) {
            log.info { "贪婪填充: 剩${me.usableResource}费 ${remaining.size}张" }
            for (c in remaining) {
                val actualCost = c.actualCost(me, enemyMinions)
                if (me.usableResource >= actualCost) {
                    val curCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    val ns = KNOWN_CARD_MAP[c.cardId]?.needsSpace
                        ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                        playCardWithTargeting(c, me, rival)
                    } else if (c.cardType === CardTypeEnum.LOCATION) {
                        c.action.power()
                    } else if (!me.playArea.isFull && curCnt + ns <= 7) {
                        playCardWithTargeting(c, me, rival)
                    }
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }

        // 10. 地标二次激活
        plays = me.playArea.cards.toList()
        DeckStrategyUtil.activeLocation(plays)

        // 11. 英雄技能
        heroPower?.let { p ->
            if (me.usableResource >= p.cost) {
                val hasPlayable = me.handArea.cards.any {
                    val ac = it.actualCost(me, enemyMinions)
                    ac <= me.usableResource &&
                        (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.SPELL)
                }
                if (!hasPlayable || me.usableResource >= p.cost + 3) {
                    log.info { "英雄技能" }
                    p.action.power()
                    Thread.sleep((100..200).random().toLong())
                }
            }
        }

        // 12. 激发
        me.playArea.cards.toList().forEach { c ->
            if (c.isLaunchpad && me.usableResource >= c.launchCost()) {
                c.action.launch()
                Thread.sleep((80..150).random().toLong())
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
        val hasTaunt = rival.playArea.cards.any { it.cardType == CardTypeEnum.MINION && it.isEnemyTauntLike() }
        return myAtk >= rivalHp && !hasTaunt
    }

    private fun rivalHp(): Int {
        val hero = WAR.rival.playArea.hero ?: return 99
        return hero.health + hero.armor - hero.damage
    }

    private fun isNearLethal(me: Player, rival: Player): Boolean {
        val myAtk = me.playArea.cards
            .filter { it.atc > 0 && (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.HERO) }
            .sumOf { it.atc }
        val hero = rival.playArea.hero ?: return false
        val rivalHp = hero.health + hero.armor - hero.damage
        val hasTaunt = rival.playArea.cards.any { it.cardType == CardTypeEnum.MINION && it.isEnemyTauntLike() }
        return myAtk >= rivalHp * 0.7 && !hasTaunt
    }

    // ==================== 敌方嘲讽检测 ====================

    private fun Card.isEnemyTauntLike(): Boolean {
        return isTaunt || cardType == CardTypeEnum.INVALID || KNOWN_CARD_MAP[cardId]?.isTaunt == true
    }

    // ==================== 场面预清 ====================

    private fun preClearForSpace(me: Player, enemyMinions: List<Card>) {
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        if (myMinions.size < 2 || enemyMinions.isEmpty()) return

        val sortedEnemies = enemyMinions.sortedByDescending {
            (if (it.isEnemyTauntLike()) 100 else 0) + it.atc
        }
        for (enemy in sortedEnemies) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            if (attackers.isEmpty()) break

            // 优先用亡语蛋或低价值随从交换
            val egg = attackers
                .filter { it.cardId in setOf("CATA_210", "DINO_130") && it.atc >= enemy.health }
                .minByOrNull { it.atc * it.health }
            if (egg != null && (enemy.isEnemyTauntLike() || enemy.atc >= 3)) {
                log.info { "预清(蛋): ${egg.entityName}→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                egg.action.attack(enemy)
                Thread.sleep((80..150).random().toLong())
                continue
            }
            // 用低价值小怪换
            val small = attackers
                .filter { it.atc <= 2 && it.atc >= enemy.health }
                .minByOrNull { it.atc.toDouble() * it.health.toDouble() }
            if (small != null && (enemy.atc >= 2 || enemy.isEnemyTauntLike())) {
                log.info { "预清: ${small.entityName}→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                small.action.attack(enemy)
                Thread.sleep((80..150).random().toLong())
            }
        }
    }

    // ==================== 自定义DP ====================

    private fun customDP(
        cards: List<Card>,
        mana: Int,
        enemies: List<Card>,
        myMinionCount: Int,
        freeSpace: Int,
        hasTokensOnBoard: Boolean = false,
        hasBuffInHand: Boolean = false,
    ): Pair<Double, List<SimulateWeightCard>> {
        if (cards.isEmpty() || mana <= 0) return Pair(0.0, emptyList())
        val me = WAR.me
        val n = cards.size
        val vals = DoubleArray(n) { i ->
            calcValue(cards[i], mana, enemies, myMinionCount, freeSpace, hasTokensOnBoard, hasBuffInHand)
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
        myMinionCount: Int = 0,
        freeSpace: Int = 7,
        hasTokensOnBoard: Boolean = false,
        hasBuffInHand: Boolean = false,
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

        // 覆盖 INVALID 属性
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

        if (known != null) {
            v += known.bonus
        }

        // 跳费价值：越高费越不值（后期跳费意义小），低费阶段跳费价值极高
        if (known?.isRamp == true) {
            val currentMana = WAR.me.usableResource
            v += when {
                currentMana <= 4 -> 8.0    // 前期跳费极其重要
                currentMana <= 6 -> 5.0    // 中期仍有价值
                currentMana <= 8 -> 2.5    // 后期价值降低
                else -> 1.0
            }
        }

        // 身材效率
        if (c.cost > 0 && effectiveType == CardTypeEnum.MINION) {
            val stats = effectiveAtc + effectiveHealth
            v += stats.toDouble() / c.cost * 0.6
            // 大身材绝对价值
            if (effectiveAtc >= 5) v += effectiveAtc * 0.2
            if (effectiveAtc + effectiveHealth >= 10) v += 1.0
        }

        // 铺场token价值：空格子越多越值钱，空格少时惩罚
        if (known?.isTokenGenerator == true) {
            val tokens = known.tokensGenerated
            if (tokens > 0) {
                val actualTokens = tokens.coerceAtMost(freeSpace)
                v += actualTokens * 2.0  // 每个token基础价值
                if (actualTokens < tokens) {
                    v -= (tokens - actualTokens) * 5.0  // 没法全部下场的惩罚
                }
            }
            // 法术token牌在有空位时额外加分
            if (effectiveType == CardTypeEnum.SPELL && freeSpace >= 2) v += 1.5
        }

        // 群体buff价值：随从越多越值钱
        if (known?.isBuff == true) {
            val effectiveTargets = myMinionCount.coerceAtMost(7)
            v += known.buffValue * effectiveTargets * 1.2
            // 有铺场牌但没场面，buff价值低
            if (myMinionCount <= 1 && c.cost >= 2) v -= 2.0
            // 已有多铺场随从，buff价值高
            if (hasTokensOnBoard && myMinionCount >= 3) v += 3.0
        }

        // 过牌价值
        if (known?.isDraw == true) {
            val handSize = WAR.me.handArea.cards.size
            v += known.drawCount * 1.5
            // 手牌少时过牌更迫切
            if (handSize <= 3) v += 2.0
            else if (handSize <= 5) v += 1.0
            // 手牌多时过牌价值低(防爆牌)
            if (handSize >= 8) v -= 2.0
            else if (handSize >= 6) v -= 0.5
        }

        // 关键词价值
        if (effectiveType == CardTypeEnum.MINION) {
            if (effectiveTaunt) { v += 1.0; if (enemies.size >= 3) v += 1.5 }
            if (effectiveRush) { v += 2.0; if (enemies.isNotEmpty()) v += 1.0 }
            if (effectiveCharge) { v += 2.5; if (enemies.isEmpty()) v += 1.0 }
            if (effectivePoisonous) { v += 3.0; if (enemies.any { it.atc >= 6 }) v += 2.0 }
            if (effectiveLifesteal) v += 1.0
            if (effectiveDivineShield) v += 1.0
            if (effectiveReborn) v += 0.8
            if (known?.isElusive == true) v += 0.8
        }

        // 地标
        if (effectiveType == CardTypeEnum.LOCATION) v += 3.0

        // 法术
        if (effectiveType == CardTypeEnum.SPELL) {
            if (c.cost <= 1) v += 1.0
            if (enemies.isNotEmpty() && c.cost <= 3) v += 1.0
        }

        // 抉择牌（活体根须）：默认选择铺场选项
        if (known?.isChooseOne == true) {
            v += 1.0  // 灵活性加分
        }

        // 费用适配
        val actualCost = c.actualCost(WAR.me, enemies)
        if (actualCost > 0 && actualCost <= mana) {
            v += actualCost.toDouble() / mana.coerceAtLeast(1) * 0.8
        }

        // 回合结束效果
        if (known?.endOfTurnValue ?: 0.0 > 0) {
            v += known!!.endOfTurnValue
        }

        return v
    }

    // ==================== 卡牌实际费用 ====================

    private fun Card.actualCost(me: Player, enemies: List<Card>): Int {
        // 暂时没有动态费用的牌（后续如有可在此处理）
        return this.cost
    }

    // ==================== 主动解场 ====================

    private fun clearHighThreatsOnly() {
        val me = WAR.me
        val rival = WAR.rival
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        val highThreats = rival.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc >= 5 }
            .sortedByDescending { it.atc }
        if (myMinions.isEmpty() || highThreats.isEmpty()) return

        for (enemy in highThreats) {
            val attacker = myMinions
                .filter { !it.isExhausted && it.atc > 0 && it.atc >= enemy.health }
                .minByOrNull { it.atc * it.health }
            if (attacker != null) {
                log.info { "解高威胁: ${attacker.entityName}(${attacker.atc}/${attacker.health})→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                attacker.action.attack(enemy)
                Thread.sleep((80..150).random().toLong())
            }
        }
    }

    private fun activeClear() {
        val me = WAR.me
        val rival = WAR.rival
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        val enemyMinions = rival.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION }
            .sortedByDescending {
                (if (it.isEnemyTauntLike()) 50 else 0) + it.atc
            }
        if (myMinions.isEmpty() || enemyMinions.isEmpty()) return

        for (enemy in enemyMinions) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            if (attackers.isEmpty()) break

            var best: Card? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (a in attackers) {
                var score = -a.atc.toDouble() * a.health.toDouble()
                if (a.atc >= enemy.health) score += 20.0  // 能一次解掉
                if (a.isRush || a.isCharge) score += 8.0
                if (a.isPoisonous) score += 15.0  // 剧毒最优
                // 亡语蛋优先送掉触发效果
                if (a.cardId in setOf("CATA_210", "DINO_130")) score += 10.0
                // 优先用低价值小怪
                if (a.atc <= 2 && a.atc >= enemy.health) score += 6.0
                // 嘲讽留着护脸
                if (a.isTaunt) score -= 4.0
                // 不能击杀惩罚
                if (a.atc < enemy.health && !a.isPoisonous) score -= 15.0
                if (score > bestScore) { bestScore = score; best = a }
            }
            val attacker = best ?: continue

            val canKill = attacker.atc >= enemy.health || attacker.isPoisonous
            val isThreat = enemy.atc >= 3 || enemy.isEnemyTauntLike()
            val should = when {
                canKill && isThreat -> true
                canKill && (attacker.isRush || attacker.isCharge) -> true
                canKill && attacker.cardId in setOf("CATA_210", "DINO_130") -> true
                attacker.atc >= 4 && enemy.atc <= 1 -> false
                attacker.atc <= 2 && canKill && enemy.atc >= 4 -> true
                else -> false
            }
            if (should) {
                log.info { "主动解场: ${attacker.entityName}(${attacker.atc}/${attacker.health})→${enemy.entityName}(${enemy.atc}/${enemy.health})" }
                attacker.action.attack(enemy)
                Thread.sleep((80..150).random().toLong())
            }
        }
    }

    // ==================== 出牌辅助 ====================

    private fun playCardWithTargeting(c: Card, me: Player, rival: Player) {
        val cardInfo = CARD_DATA_TRIE[c.cardId]
        val known = KNOWN_CARD_MAP[c.cardId]

        // 抉择牌
        if (known?.isChooseOne == true) {
            c.action.power()
            Thread.sleep((200..350).random().toLong())
            c.action.chooseOne(known.chooseOneIndex)
            return
        }

        // 需要指向的牌
        if (known?.needsTargeting == true) {
            val targets = if (known.targetsEnemy) {
                rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByRivalSpells() }
            } else {
                me.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByMySpells() }
            }
            if (targets.isNotEmpty()) {
                // 友方目标：优先选低攻高血（变成5/4最赚）
                val target = if (known.targetsEnemy) {
                    targets.maxByOrNull { it.atc * 2 + it.health }
                } else {
                    // 奔行豹面具：选低攻高血随从（变成5/4最赚）
                    targets.filter { it.atc <= 3 && it.health >= 3 }
                        .maxByOrNull { it.health - it.atc }
                        ?: targets.maxByOrNull { it.health - it.atc }
                }
                if (target != null) {
                    log.info { "指向出牌: ${c.entityName}→${target.entityName}(${target.atc}/${target.health})" }
                    c.action.power(target)
                    return
                }
            }
            log.info { "指向出牌(${c.entityName})无可用目标，尝试普通打出" }
            c.action.power()
            return
        }

        // 普通出牌
        c.action.autoPower(cardInfo)
    }

    // ==================== 排序 ====================

    private fun sortCards(
        cards: List<SimulateWeightCard>,
        myMinionCount: Int,
        hasTokensOnBoard: Boolean,
    ): List<SimulateWeightCard> {
        return cards.sortedBy { swc ->
            val c = swc.card
            val known = KNOWN_CARD_MAP[c.cardId]
            when {
                // 1. 跳费牌最先 (获得更多法力)
                known?.isRamp == true -> -10
                // 2. 地标
                known?.isLocation == true -> -5
                c.cardType == CardTypeEnum.LOCATION -> -4
                // 3. 亡语蛋（先下可以后续主动送掉）
                c.cardId in setOf("CATA_210", "DINO_130") -> -2
                // 4. 费用0
                c.cost == 0 -> 0
                // 5. 铺场token低费牌
                known?.isTokenGenerator == true && c.cost <= 2 -> 5
                known?.isTokenGenerator == true -> 10
                // 6. 低费战吼随从（栉龙抽牌等）
                c.cost in 1..2 && c.isBattlecry -> 15
                // 7. 过牌
                known?.isDraw == true -> 20
                // 8. buff牌（场面有随从时早出，无随从时晚出）
                known?.isBuff == true -> if (hasTokensOnBoard) 12 else 40
                // 9. 随从
                c.cardType == CardTypeEnum.MINION -> 30
                // 10. 法术
                c.cardType == CardTypeEnum.SPELL -> 50
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

        // 爆牌预防
        val handSize = me.handArea.cards.size
        if (handSize >= 9) {
            if (c.cost <= 1) s += 3.0
            else if (c.cost <= 3) s += 1.0
            else if (c.cost >= 5) s -= 4.0
        } else if (handSize >= 8) {
            if (c.cost <= 2) s += 1.5
            else if (c.cost >= 6) s -= 3.0
        } else if (handSize >= 7) {
            if (c.cost <= 3) s += 0.5
            else if (c.cost >= 7) s -= 1.5
        }

        // 覆盖未知属性
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

        // 身材效率
        if (c.cost > 0 && effType == CardTypeEnum.MINION) {
            s += (effAtc + effHealth).toDouble() / c.cost * 0.6
        }

        // 费用匹配
        if (c.cost <= me.usableResource) s += 0.35
        else if (c.cost > me.usableResource + 3) s -= 0.4

        // 铺场德发现偏好
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val enemyMinions = WAR.rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val enemyAtk = enemyMinions.sumOf { it.atc }

        // 偏好铺场/群体buff
        if (known?.isTokenGenerator == true) {
            val freeSlots = 7 - myMinionCount
            s += known.tokensGenerated.coerceAtMost(freeSlots) * 0.8
        }
        if (known?.isBuff == true && myMinionCount >= 2) s += 1.2
        if (known?.isRamp == true && me.usableResource <= 6) s += 1.0

        // 关键词
        if (c.isTaunt) {
            s += 0.3
            if (enemyAtk >= 6 || (me.playArea.hero?.let { it.health + it.armor - it.damage } ?: 30) < 15) s += 0.6
        }
        if (c.isRush && enemyMinions.isNotEmpty()) s += 0.6
        if (c.isPoisonous) s += 0.5
        if (c.isDivineShield) s += 0.2
        if (effBattlecry) s += 0.2
        if (c.isCharge) s += 0.4

        // 法术
        if (effType == CardTypeEnum.SPELL && c.cost <= 3) s += 0.3

        // 大身材
        if (effType == CardTypeEnum.MINION && effAtc >= 6) s += effAtc * 0.1

        CARD_DATA_TRIE[c.cardId]?.let { cd -> s += cd.weight * 0.1 }
        return s
    }

    // ==================== 回溯（时间线） ====================

    override fun execChooseTimeLine(timeLineEvent: TimelineEvent) {
        val me = WAR.me
        // 有一定铺场则倾向于维持，否则回溯
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val hasGoodBoard = myMinionCount >= 3
        val score = scoreBoard(me)
        val threshold = if (hasGoodBoard) 0.4 else 0.55
        log.info { "时间线评分=${"%.2f".format(score)} 阈值=$threshold 场面${myMinionCount}随从 → ${if (score >= threshold) "维持" else "回溯"}" }
        if (score >= threshold) timeLineEvent.keep() else timeLineEvent.rewind()
    }

    private fun scoreBoard(me: Player): Double {
        var s = 0.5
        val hero = me.playArea.hero ?: return 0.3
        val maxHp = hero.health + hero.armor
        val curHp = maxHp - hero.damage
        val hpRatio = (curHp.toDouble() / maxHp).coerceIn(0.0, 1.0)
        s += (hpRatio - 0.5) * 0.4
        s += (me.playArea.cards.size - 3).coerceIn(-2, 4) * 0.05
        s += (me.handArea.cards.size - 3).coerceIn(-3, 3) * 0.04
        val tokenCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION && it.atc <= 2 }
        s += (tokenCount - 1).coerceIn(-1, 4) * 0.04
        if (me.usableResource >= 5) s += 0.1
        val rival = WAR.rival
        val enemyCount = rival.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val enemyAtk = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }.sumOf { it.atc }
        s -= (enemyCount - 2).coerceAtLeast(0) * 0.07
        if (enemyAtk >= 8) s -= 0.12
        if (me.handArea.cards.size <= 2) s -= 0.06
        return s.coerceIn(0.0, 1.0)
    }

    // ==================== 兜底攻击 ====================

    private fun postCleanUpAttacks(me: Player, rival: Player) {
        val unchecked = me.playArea.cards.filter {
            it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted
        }
        if (unchecked.isEmpty()) return

        val enemyTaunts = rival.playArea.cards.filter {
            it.cardType == CardTypeEnum.MINION && !it.isExhausted && it.isEnemyTauntLike()
        }
        val enemyHero = rival.playArea.hero

        if (enemyTaunts.isNotEmpty()) {
            for (taunt in enemyTaunts.sortedByDescending { it.atc }) {
                val attacker = unchecked
                    .filter { !it.isExhausted && it.atc > 0 }
                    .minByOrNull { it.atc.toDouble() * it.health.toDouble() }
                if (attacker != null && !attacker.isExhausted) {
                    log.info { "兜底解嘲讽: ${attacker.entityName}→${taunt.entityName}" }
                    attacker.action.attack(taunt)
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }

        // 无嘲讽打脸
        val stillUnchecked = me.playArea.cards.filter {
            it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted
        }
        if (stillUnchecked.isNotEmpty() && enemyHero != null) {
            val stillHasTaunt = rival.playArea.cards.any {
                it.cardType == CardTypeEnum.MINION && !it.isExhausted && it.isEnemyTauntLike()
            }
            if (!stillHasTaunt) {
                for (m in stillUnchecked.sortedByDescending { it.atc }) {
                    if (!m.isExhausted && m.atc > 0) {
                        log.info { "兜底打脸: ${m.entityName}(${m.atc}/${m.health})→敌方英雄" }
                        m.action.attack(enemyHero)
                        Thread.sleep((80..150).random().toLong())
                    }
                }
            }
        }
    }

    override fun reset() {
        super.reset()
    }
}
