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
        isBattlecry = true, isRamp = true, bonus = 3.0), // 永久转化额外+3.0价值
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
    "CATA_139" to KnownCardInfo(  // 柳牙 6费0/5 巨型+4（4个0/2腿，每回合腿+1/+1→本体复制）
        cardType = CardTypeEnum.MINION, atc = 0, health = 5,
        isTokenGenerator = true, tokensGenerated = 4, needsSpace = 5, bonus = 5.0),
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
    // --- 圣骑士时序光环/奇闻相关嘲讽 ---
    "TIME_321" to KnownCardInfo(  // 奇闻光环可能赋予嘲讽的随从
        cardType = CardTypeEnum.MINION, isTaunt = true),
    "TIME_433" to KnownCardInfo(  // 时序光环相关
        cardType = CardTypeEnum.MINION, isTaunt = true),
    "CORE_DRG_237" to KnownCardInfo(  // 庇护 2费2/4 龙 嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        cardRace = CardRaceEnum.DRAGON, isTaunt = true),
    "CORE_CS2_188" to KnownCardInfo(  // 阿曼尼狂战士 2费2/3 → 激怒后可能嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 3),
    "CORE_CS2_179" to KnownCardInfo(  // 森金持盾卫士 4费3/5 嘲讽 (别名)
        cardType = CardTypeEnum.MINION, atc = 3, health = 5, isTaunt = true),
    // --- 龙巢/发现相关嘲讽 ---
    "CATA_469" to KnownCardInfo(  // 多彩龙巢母 - 如果赋予嘲讽
        cardType = CardTypeEnum.MINION),
    "TLC_600" to KnownCardInfo(  // 乘风浮龙 - 可能获得嘲讽
        cardType = CardTypeEnum.MINION),
)

// ==================== 铺场德-v2 策略主类 ====================

class TokenDruidDeck : DeckStrategy() {

    // 费伍德树人永久水晶转化：记录剩余需浮动的法力值（打出后需留4费转化）
    private var manaToFloatForPermanent = 0
    // 柳牙是否已打出（避免重复扣除格子）
    private var wickerfangPlayed = false

    override fun name(): String = "铺场德-v2"

    override fun description(): String =
        "铺场德v2：跳费铺场+群体buff+地标协同+亡语赖场，抉择牌反射修复"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAfHGBwTDgwevhwe4nwfgwAcNrp8EgdQEiIMHrocHkpcHlJcH15cH2p0Hqq8H18AH28AH7MAH9sEHAAA="

    override fun id(): String = "token-druid-deck-v2"

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
        val hasTokenGenInHand = myCards.any { KNOWN_CARD_MAP[it.cardId]?.isTokenGenerator == true }
        val myTokenCount = me.playArea.cards.count {
            it.cardType == CardTypeEnum.MINION && it.atc <= 2
        }
        val hasTokensOnBoard = myTokenCount >= 2
        // 手上有buff时，铺场牌应该获得额外优先级（先铺后buff）
        val shouldPrioritizeTokens = hasBuffInHand && myMinionCount < 4

        // 3.5 费伍德树人永久水晶转化：有树人场面时预留法力转化
        val hasFelwoodOnBoard = me.playArea.cards.any { it.cardId == "CATA_131" }
        val reserveForPermanent = if (hasFelwoodOnBoard && manaToFloatForPermanent > 0) {
            log.info { "预留法力转化永久水晶: 剩余需浮动${manaToFloatForPermanent}费" }
            minOf(manaToFloatForPermanent, 2)  // 每回合最多预留2费
        } else 0
        val dpMana = (me.usableResource - reserveForPermanent).coerceAtLeast(0)

        // 4. 自定义DP
        val (dpScore, dpCards) = customDP(myCards, dpMana, enemyMinions,
            myMinionCount, freeSpace, hasTokensOnBoard, hasBuffInHand)
        val dpFmt = "%.1f".format(dpScore)
        log.info { "DP得分${dpFmt} 选中${dpCards.size}张" }

        var finalCards = dpCards

        // 5. 硬币评估：仅在能多出牌或出更高费关键牌时使用
        val coin = DeckStrategyUtil.findCoin(hands)
        if (coin != null && me.usableResource <= 6) {
            val (cScore, cCards) = customDP(myCards, dpMana + 1, enemyMinions,
                myMinionCount, freeSpace, hasTokensOnBoard, hasBuffInHand)
            val coinCardsCost = cCards.sumOf { it.card.actualCost(me, enemyMinions) }
            val noCoinCardsCost = dpCards.sumOf { it.card.actualCost(me, enemyMinions) }
            // 硬币必须让总消耗更多（确实施放了额外资源）或能提前出柳牙
            val enablesWickerfang = cCards.any { it.card.cardId == "CATA_139" } && !dpCards.any { it.card.cardId == "CATA_139" }
            if ((cScore > dpScore + 2.0 && coinCardsCost > noCoinCardsCost) || enablesWickerfang) {
                val cFmt = "%.1f".format(cScore)
                val extra = if (enablesWickerfang) " (出柳牙!)" else ""
                log.info { "硬币 得分${cFmt}${extra}" }
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
                val label = swc.card.entityName.ifEmpty { swc.card.cardId.ifEmpty { "?" } }
                log.info { "  $label(${swc.card.cost}费)[v=${v}]" }
            }
            var used = 0
            var firstAction = true
            for (swc in sorted) {
                val c = swc.card
                if (me.usableResource >= c.actualCost(me, enemyMinions)) {
                    val known = KNOWN_CARD_MAP[c.cardId]
                    val curMinionCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    // 场面太小(<2随从)时不浪费法术buff牌，等后续铺场
                    // 随从型buff（如长颈龙蛋）不跳过，因为可以独立站场
                    if (known?.isBuff == true && c.cardType != CardTypeEnum.MINION &&
                        curMinionCnt < 2 && !nearLethal) {
                        log.info { "推迟buff: ${c.entityName.ifEmpty { c.cardId }} 场面仅${curMinionCnt}随从" }
                        continue
                    }
                    val needSpace = known?.needsSpace
                        ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    // 柳牙：需要5个格子（1本体+4腿），格子不够先主动清场
                    if (c.cardId == "CATA_139" && curMinionCnt + 5 > 7 && !hasLethal && !nearLethal) {
                        log.info { "柳牙前清场: 当前${curMinionCnt}随从 需5格" }
                        preClearForWickerfang(me, curMinionCnt)
                    } else if (curMinionCnt + needSpace > 7 && !hasLethal && !nearLethal) {
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
                    // 费伍德树人：记录需转化永久水晶
                    if (c.cardId == "CATA_131") {
                        manaToFloatForPermanent = 4
                        log.info { "费伍德树人: 开始转化永久水晶(需浮动4费)" }
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

        // 9. 贪婪填充：用尽剩余法力，但避免浪费buff
        val curMinionCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val updatedFreeSpace = 7 - curMinionCnt
        val hasTokensNow = me.playArea.cards.count {
            it.cardType == CardTypeEnum.MINION && it.atc <= 2
        } >= 2
        val remaining = me.handArea.cards.toList()
            .filter { !it.isCoinCard && it.actualCost(me, enemyMinions) <= me.usableResource }
            .filter {
                // 场面 ≤2 随从时不填充 buff 牌（留到后面用）
                val k = KNOWN_CARD_MAP[it.cardId]
                !(k?.isBuff == true && curMinionCnt <= 2)
            }
            .sortedByDescending { calcValue(it, me.usableResource, enemyMinions, curMinionCnt, updatedFreeSpace, hasTokensNow, hasBuffInHand) }
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

        // 11. 英雄技能：剩余法力多时使用，或有1血敌方随从可咬
        heroPower?.let { p ->
            if (me.usableResource >= p.cost) {
                val myCards = me.handArea.cards
                val hasPlayable = myCards.any {
                    val ac = it.actualCost(me, enemyMinions)
                    ac <= me.usableResource &&
                        (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.SPELL)
                }
                // 有1血随从可咬 → 用技能解场
                val hasPingTarget = enemyMinions.any { it.health <= 1 }
                // 没牌出、剩余法力≥技能+1(会浪费法力)、或有值得咬的目标
                if (!hasPlayable || me.usableResource >= p.cost + 1 || hasPingTarget) {
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

        // 13. 永久水晶转化追踪：扣除本回合预留的法力
        if (manaToFloatForPermanent > 0) {
            val floated = reserveForPermanent + maxOf(0, me.usableResource)
            manaToFloatForPermanent = maxOf(0, manaToFloatForPermanent - floated)
            if (manaToFloatForPermanent == 0) {
                log.info { "永久水晶转化完成!" }
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

    /** 柳牙专用清场：确保至少腾出5-当前随从数的格子 */
    private fun preClearForWickerfang(me: Player, curMinionCnt: Int) {
        val needToClear = curMinionCnt + 5 - 7  // 需要清掉的随从数
        if (needToClear <= 0) return
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
            .sortedBy { it.atc * it.health }  // 先清最弱的
        var cleared = 0
        for (m in myMinions) {
            if (cleared >= needToClear) break
            val enemyTargets = WAR.rival.playArea.cards
                .filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByRivalSpells() }
                .sortedByDescending { it.atc }
            if (enemyTargets.isNotEmpty()) {
                log.info { "柳牙清格: ${m.entityName}(${m.atc}/${m.health})→${enemyTargets[0].entityName}(${enemyTargets[0].atc}/${enemyTargets[0].health})" }
                m.action.attack(enemyTargets[0])
                Thread.sleep((80..150).random().toLong())
            }
            // 如果清完还是不够，可以考虑送掉蛋或低价值随从
            val stillNeed = (me.playArea.cards.count { it.cardType == CardTypeEnum.MINION } - cleared - 1) + 5 - 7
            if (stillNeed <= 0) break
            cleared++
        }
        // 如果仍然不够格子，送掉0攻蛋
        val stillToClear = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION } + 5 - 7
        if (stillToClear > 0) {
            val eggs = me.playArea.cards
                .filter { it.cardType == CardTypeEnum.MINION && it.atc == 0 }
            for (egg in eggs.take(stillToClear)) {
                val enemyTargets = WAR.rival.playArea.cards
                    .filter { it.cardType == CardTypeEnum.MINION }
                    .sortedByDescending { it.atc }
                if (enemyTargets.isNotEmpty()) {
                    log.info { "柳牙送蛋: ${egg.entityName}→${enemyTargets[0].entityName}" }
                    egg.action.attack(enemyTargets[0])
                    Thread.sleep((80..150).random().toLong())
                }
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

        // 跳费价值：只在前中期有价值，且必须有可用的后续牌
        if (known?.isRamp == true) {
            val currentMana = WAR.me.usableResource
            if (currentMana <= 6) {
                val handCards = WAR.me.handArea.cards
                // 检查手牌中是否有值得跳费出的牌（费用>当前可用法力 且 非跳费牌本身）
                val hasFollowUp = handCards.any {
                    it.cardId != c.cardId && it.actualCost(WAR.me, enemies) in (currentMana + 1)..(currentMana + 2)
                }
                v += when {
                    currentMana <= 3 && hasFollowUp -> 8.0   // 前期有后续 → 极值
                    currentMana <= 3 -> 4.0                   // 前期无后续 → 降值
                    currentMana <= 5 && hasFollowUp -> 5.0   // 中期有后续
                    currentMana <= 5 -> 2.0                   // 中期无后续
                    else -> 1.5
                }
                // 费伍德树人：如果能获得永久法力水晶（预留法力转化），额外加分
                if (c.cardId == "CATA_131") {
                    // 检查是否有激活配合（激活+树人=双跳费）
                    val hasInnervate = handCards.any { it.cardId == "CORE_EX1_169" }
                    if (hasInnervate && currentMana <= 4) v += 3.0
                    // 永久水晶转化价值：如果能在低费阶段转化，价值极高
                    if (currentMana <= 5) v += 2.0  // 永久水晶的长期收益
                }
            } else {
                v += 0.5  // 后期跳费几乎无用
            }
        }

        // 身材效率
        if (c.cost > 0 && effectiveType == CardTypeEnum.MINION) {
            val stats = effectiveAtc + effectiveHealth
            v += stats.toDouble() / c.cost * 0.6
            if (effectiveAtc >= 5) v += effectiveAtc * 0.2
            if (effectiveAtc + effectiveHealth >= 10) v += 1.0
        }

        // 铺场token价值：空格子越多越值钱，空格少时惩罚
        if (known?.isTokenGenerator == true) {
            val tokens = known.tokensGenerated
            if (tokens > 0) {
                val actualTokens = tokens.coerceAtMost(freeSpace)
                v += actualTokens * 2.0
                if (actualTokens < tokens) {
                    v -= (tokens - actualTokens) * 5.0
                }
            }
            if (effectiveType == CardTypeEnum.SPELL && freeSpace >= 2) v += 1.5
            if (c.cardId == "CATA_134") {
                v += myMinionCount * 1.8
                if (myMinionCount >= 3) v += 2.0
            }
            // 铺场牌是 buff 的前置条件：手牌有 buff 时铺场价值提升
            if (hasBuffInHand && freeSpace >= 2) v += 3.0
            // 手上有buff且场上随从少→铺场更紧急
            if (hasBuffInHand && myMinionCount < 3 && freeSpace >= 3) v += 2.0
        }

        // 柳牙（CATA_139）终端评价
        if (c.cardId == "CATA_139") {
            // 空间不够 → 严重惩罚（巨型+4需要5格）
            if (freeSpace < 5) {
                v -= (5 - freeSpace) * 4.0  // 每缺1格扣4分
            }
            // 有荒林怪圈在场 → 协同加分（柳牙的腿死后触发亡语2/2）
            if (WAR.me.playArea.cards.any { it.cardId == "CATA_134" }) {
                v += 4.0
            }
            // 手上有buff → 柳牙铺满后可buff的协同价值
            if (hasBuffInHand) v += 3.0
            // 场上已有随从时，柳牙价值降低（占据格子）
            if (myMinionCount >= 3) v -= (myMinionCount - 2) * 1.5
            // 费用够出柳牙+预留激活/硬币 → 额外加分（能提前出）
            if (mana >= c.cost && WAR.me.handArea.cards.any {
                it.cardId == "CORE_EX1_169" || it.isCoinCard
            }) v += 2.0
        }

        // 群体buff价值：随从数越多越值钱（铺场德核心：先铺后buff）
        if (known?.isBuff == true) {
            v += when {
                myMinionCount >= 5 -> known.buffValue * myMinionCount * 1.8 + 5.0  // 满场buff极值
                myMinionCount >= 4 -> known.buffValue * myMinionCount * 1.5 + 3.0
                myMinionCount >= 3 -> known.buffValue * myMinionCount * 1.2 + 1.5
                myMinionCount == 2 -> known.buffValue * 2.0  // 2随从buff勉强可用
                myMinionCount == 1 -> -2.0                    // 1随从buff严重浪费
                else -> -5.0                                  // 0随从buff是废牌
            }
            // 手牌有未使用的铺场牌时，buff应让位
            val hasTokenInHand = WAR.me.handArea.cards.any {
                val k = KNOWN_CARD_MAP[it.cardId]
                k?.isTokenGenerator == true && it.cardId != c.cardId &&
                    it.actualCost(WAR.me, enemies) <= mana
            }
            if (hasTokenInHand && myMinionCount < 3) v -= 3.0
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

        // 抉择牌：根据场面动态选择模式
        // 注意: 不能使用 autoPower(cardInfo)，因为 SDK 解析的行动会跳过抉择 UI
        if (known?.isChooseOne == true) {
            val chosenIndex = when (c.cardId) {
                // 活体根须：敌方有低血威胁(≤2血 且 ≥3攻 或嘲讽)→打2解场，否则铺场
                "CORE_AT_037" -> {
                    val weakThreats = rival.playArea.cards.filter {
                        it.cardType == CardTypeEnum.MINION && it.health <= 2 &&
                            (it.atc >= 3 || it.isEnemyTauntLike())
                    }
                    if (weakThreats.isNotEmpty()) 0 else 1
                }
                else -> known.chooseOneIndex
            }
            val cardLabel = c.entityName.ifEmpty { c.cardId }
            val modeLabel = if (chosenIndex == 0) "打2" else "铺场"
            log.info { "抉择: $cardLabel→$modeLabel(index=$chosenIndex) cardId=${c.cardId}" }
            try {
                // Step 1: 拖拽卡牌打出触发抉择 UI（power() 内置延迟等待游戏处理）
                if (c.action.power() == null) {
                    log.warn { "抉择: power()失败，重试" }
                    Thread.sleep(600)
                    c.action.power()
                }
                // Step 2: 等待抉择 UI 渲染后点击选项
                Thread.sleep((500..800).random().toLong())
                chooseOneFixed(chosenIndex)
            } catch (e: InterruptedException) {
                log.warn { "抉择被中断: $cardLabel" }
                Thread.currentThread().interrupt()
            }
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
                // 奔行豹面具：选低攻高血随从（变成5/4最赚）
                val target = if (known.targetsEnemy) {
                    targets.maxByOrNull { it.atc * 2 + it.health }
                } else {
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
                // 5. 荒林怪圈：亡语buff协同，优先于一般铺场
                c.cardId == "CATA_134" -> 3
                // 6. 柳牙：后于荒林怪圈(协同)，先于一般token(需腾格子)
                c.cardId == "CATA_139" -> 6
                // 7. 铺场token低费牌
                known?.isTokenGenerator == true && c.cost <= 2 -> 8
                known?.isTokenGenerator == true -> 12
                // 7. 低费战吼随从（栉龙抽牌等）
                c.cost in 1..2 && c.isBattlecry -> 15
                // 8. 过牌
                known?.isDraw == true -> 20
                // 9. buff牌：铺场德核心是先铺再buff，随从少时严格后置
                known?.isBuff == true -> when {
                    myMinionCount >= 4 -> 10   // ≥4随从 buff急出
                    myMinionCount >= 3 -> 15   // 3随从 buff可出
                    myMinionCount == 2 -> 30   // 2随从 buff勉强
                    else -> 80                 // ≤1随从 buff不出（等铺场）
                }
                // 10. 随从
                c.cardType == CardTypeEnum.MINION -> 30
                // 11. 法术
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

    /**
     * 抉择选项点击（反射绕过核心JAR的execChooseOne中lClick()右击取消问题）
     */
    @Suppress("UNCHECKED_CAST")
    private fun chooseOneFixed(index: Int): Boolean {
        return try {
            val gameUtilClass = Class.forName("club.xiaojiawei.hsscript.utils.GameUtil")
            val instanceField = gameUtilClass.getDeclaredField("INSTANCE")
            val instance = instanceField.get(null)
            val rect = gameUtilClass.getMethod("getChooseOneCardRect", Int::class.java).invoke(instance, index)
            val rectClass = rect.javaClass
            val valid = rectClass.getMethod("isValid").invoke(rect) as Boolean
            if (valid) {
                rectClass.getMethod("lClick", java.lang.Boolean.TYPE).invoke(rect, false)
                true
            } else false
        } catch (e: Exception) {
            log.warn { "chooseOneFixed反射失败: ${e.message}" }
            false
        }
    }

    override fun reset() {
        super.reset()
    }
}
