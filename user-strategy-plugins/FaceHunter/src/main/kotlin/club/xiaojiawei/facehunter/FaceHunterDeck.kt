package club.xiaojiawei.facehunter

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
    // 快攻猎专属
    val isDirectDamage: Boolean = false,       // 直伤法术 (打脸)
    val directDamageValue: Int = 0,            // 直伤数值
    val isFaceMinion: Boolean = false,          // 适合抢脸的随从
    val isBoardBuff: Boolean = false,           // 群体buff (希尔瓦娜斯的胜利)
    val buffAttack: Int = 0,                   // buff攻击力增量
    val isDraw: Boolean = false,               // 过牌/发现
    val drawCount: Int = 0,                    // 过牌数量
    val isTokenGenerator: Boolean = false,     // 召唤token
    val tokensGenerated: Int = 0,             // token数量
    val needsTargeting: Boolean = false,       // 需要手动指向
    val targetsEnemy: Boolean = false,         // 指向敌方
    val triggersTimeline: Boolean = false,     // 触发时间线选择
    val endOfTurnValue: Double = 0.0,          // 回合结束效果
    val isFreeze: Boolean = false,             // 冻结效果 (冰川裂片)
    val needsSpace: Int? = null,              // 需要格子数(null=默认:随从1/法术0)
)

private val KNOWN_CARD_MAP: Map<String, KnownCardInfo> = mapOf(
    // --- 1费随从 ---
    "CORE_UNG_205" to KnownCardInfo(  // 冰川裂片 1费2/1 战吼：冻结一个敌人
        cardType = CardTypeEnum.MINION, atc = 2, health = 1,
        isBattlecry = true, isFaceMinion = true, isFreeze = true, bonus = 1.5),
    "TIME_606" to KnownCardInfo(  // 奎尔多雷造箭师 1费1/2 战吼：使你手牌中的随从+1/+1
        cardType = CardTypeEnum.MINION, atc = 1, health = 2,
        isBattlecry = true, isFaceMinion = true, bonus = 2.0),
    "DINO_434" to KnownCardInfo(  // 迅猛龙巢护工 1费2/3 野兽
        cardType = CardTypeEnum.MINION, atc = 2, health = 3,
        cardRace = CardRaceEnum.PET, isFaceMinion = true, bonus = 2.0),
    "CATA_558" to KnownCardInfo(  // 进击的募援官 1费2/1 战吼：召唤一个1/1的募援官
        cardType = CardTypeEnum.MINION, atc = 2, health = 1,
        isBattlecry = true, isTokenGenerator = true, tokensGenerated = 1,
        isFaceMinion = true, bonus = 2.5),
    "TLC_249" to KnownCardInfo(  // 炽烈烬火 1费2/1 亡语：对所有敌人造成1点伤害
        cardType = CardTypeEnum.MINION, atc = 2, health = 1,
        isFaceMinion = true, bonus = 2.0),
    // --- 1费法术 ---
    "CORE_DS1_185" to KnownCardInfo(  // 奥术射击 1费 造成2点伤害
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 2, bonus = 2.0),
    "CORE_BAR_801" to KnownCardInfo(  // 击伤猎物 1费 对一个随从造成1点伤害，抽一张野兽牌
        cardType = CardTypeEnum.SPELL, needsTargeting = true, targetsEnemy = true,
        isDirectDamage = true, directDamageValue = 1, isDraw = true, drawCount = 1, bonus = 1.5),
    "CORE_DS1_184" to KnownCardInfo(  // 追踪术 1费 发现你牌库中的一张牌
        cardType = CardTypeEnum.SPELL, isDraw = true, drawCount = 1, bonus = 1.5),
    "TIME_EVENT_999" to KnownCardInfo(  // 时间之沙 1费 触发时间线选择
        cardType = CardTypeEnum.SPELL, triggersTimeline = true, bonus = 5.0),
    // --- 2费随从 ---
    "TLC_427" to KnownCardInfo(  // 抛石鱼人 2费2/3 鱼人 战吼：造成1-2点伤害(随机敌人)
        cardType = CardTypeEnum.MINION, atc = 2, health = 3,
        cardRace = CardRaceEnum.UNKNOWN, isBattlecry = true,
        isFaceMinion = true, bonus = 1.8),
    "TIME_601" to KnownCardInfo(  // 拾箭龙鹰 2费2/2 野兽 战吼：可重复使用英雄技能
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        cardRace = CardRaceEnum.PET, isBattlecry = true,
        isFaceMinion = true, bonus = 1.5),
    // --- 2费法术 ---
    "CATA_557" to KnownCardInfo(  // 希尔瓦娜斯的胜利 2费 使你的随从获得+2攻击力
        cardType = CardTypeEnum.SPELL, isBoardBuff = true, buffAttack = 2, bonus = 2.5),
    "TIME_600" to KnownCardInfo(  // 精确射击 2费 造成3点伤害(敌方有随从可重复)
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 3, bonus = 2.5),
    // --- 3费随从 ---
    "TIME_609" to KnownCardInfo(  // 游侠将军希尔瓦娜斯 3费2/4 战吼AOE2 奇闻
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, isFaceMinion = true, bonus = 2.5),
    "TIME_609t1" to KnownCardInfo(  // 游侠队长奥蕾莉亚 3费2/4 战吼触发两次
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, isFaceMinion = true, bonus = 2.5),
    "TIME_609t2" to KnownCardInfo(  // 游侠新兵温蕾萨 3费2/4
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isFaceMinion = true, bonus = 2.0),
    // --- 3费法术 ---
    "CATA_560" to KnownCardInfo(  // 直面托维尔 3费 使你的随从+1/+1，随机召唤一个3费随从
        cardType = CardTypeEnum.SPELL, isBoardBuff = true, buffAttack = 1,
        isTokenGenerator = true, tokensGenerated = 1, bonus = 3.0),
    // --- 常见敌方嘲讽 ---
    "CORE_GVG_085" to KnownCardInfo(  // 吵吵机器人 2费1/2 圣盾嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 2,
        isTaunt = true, isDivineShield = true),
    "CORE_BOT_911" to KnownCardInfo(  // 青铜门卫 3费1/5 磁力嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 5, isTaunt = true),
    "CORE_EX1_048" to KnownCardInfo(  // 森金持盾卫士 4费3/5 嘲讽
        cardType = CardTypeEnum.MINION, atc = 3, health = 5, isTaunt = true),
    "CORE_ICC_807" to KnownCardInfo(  // 固守卫兵 1费1/3 嘲讽
        cardType = CardTypeEnum.MINION, atc = 1, health = 3, isTaunt = true),
    "CORE_DRG_237" to KnownCardInfo(  // 庇护 2费2/4 龙 嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        cardRace = CardRaceEnum.DRAGON, isTaunt = true),
    "CORE_OG_218" to KnownCardInfo(  // 血蹄勇士 4费2/6 嘲讽
        cardType = CardTypeEnum.MINION, atc = 2, health = 6, isTaunt = true),
    "CORE_TRL_401" to KnownCardInfo(  // 阿曼尼战熊 7费5/7 突袭嘲讽
        cardType = CardTypeEnum.MINION, atc = 5, health = 7,
        isTaunt = true, isRush = true),
)

// ==================== 快攻猎-v1 策略主类 ====================

class FaceHunterDeck : DeckStrategy() {

    override fun name(): String = "快攻猎-v1"

    override fun description(): String =
        "快攻猎v1：极限抢脸+直伤斩杀+低费铺场+英雄技能节奏+三姐妹协同"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CASUAL, RunModeEnum.PRACTICE)

    override fun deckCode(): String =
        "AAECAda+BQSZpweapwebpwfLtgcNqZ8Eqp8E054Gr5IHhZUHzpsH7p8HkKcHmKcH1K8HtMAHucAHu8AHAAA="

    override fun id(): String = "face-hunter-deck-v1"

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
        val known = KNOWN_CARD_MAP[card.cardId]

        // 1费随从最优
        if (card.cost == 1 && card.cardType == CardTypeEnum.MINION) {
            score += 0.6
            if (known?.isFaceMinion == true) score += 0.2
            if (known?.isTokenGenerator == true) score += 0.15
        }
        // 2费随从
        if (card.cost == 2 && card.cardType == CardTypeEnum.MINION) {
            score += 0.4
            if (isGoingFirst) score += 0.1  // 先手2费曲线更重要
        }
        // 直伤法术（后手留作斩杀，先手低留）
        if (known?.isDirectDamage == true) {
            score += if (isGoingFirst) 0.2 else 0.35
        }
        // 追踪术 / 时间之沙
        if (card.cardId in setOf("CORE_DS1_184", "TIME_EVENT_999")) score += 0.3
        // 希尔瓦娜斯的胜利（后手留）
        if (card.cardId == "CATA_557" && !isGoingFirst) score += 0.3
        // 3费牌
        if (card.cost == 3) {
            score += if (isGoingFirst) 0.15 else 0.3
        }
        // 高费惩罚
        if (card.cost >= 4) score -= 3.0
        // 身材效率
        if (card.cost > 0 && card.cardType == CardTypeEnum.MINION) {
            score += (card.atc + card.health).toDouble() / card.cost * 0.08
        }
        // 已知牌bonus
        if (known != null) score += known.bonus * 0.1

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

        // 1. 敌方场面评估
        val enemyMinions = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val enemyTaunts = enemyMinions.filter { it.isEnemyTauntLike() }
        val enemyAtk = enemyMinions.sumOf { it.atc }
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val freeSpace = 7 - myMinionCount
        log.info { "=== ${me.usableResource}费 手牌${me.handArea.cards.size} 我方${myMinionCount}随从(空${freeSpace}格) 敌${enemyMinions.size}个(攻${enemyAtk}) ===" }

        // 2. 斩杀检测（快攻猎核心：只要场攻够就赢）
        val hasLethal = checkLethal(me, rival)
        val nearLethal = isNearLethal(me, rival)
        if (hasLethal) log.info { "斩杀! 场攻 ≥ 敌方血量" }
        else if (nearLethal) log.info { "接近斩杀(场攻≥HP70%)" }

        // 3. 手牌处理
        val hands = me.handArea.cards.toList()
        val myCards = hands.toMutableList()
        myCards.removeAll { it.isCoinCard }

        // 4. 自定义DP
        val dpMana = me.usableResource
        val (dpScore, dpCards) = customDP(myCards, dpMana, enemyMinions,
            myMinionCount, freeSpace)
        val dpFmt = "%.1f".format(dpScore)
        log.info { "DP得分${dpFmt} 选中${dpCards.size}张" }

        var finalCards = dpCards

        // 5. 硬币评估：能多出一张随从或提前出群体buff时使用
        val coin = DeckStrategyUtil.findCoin(hands)
        if (coin != null && me.usableResource <= 5) {
            val (cScore, cCards) = customDP(myCards, dpMana + 1, enemyMinions,
                myMinionCount, freeSpace)
            val coinCardsCost = cCards.sumOf { it.card.actualCost(me, enemyMinions) }
            val noCoinCardsCost = dpCards.sumOf { it.card.actualCost(me, enemyMinions) }
            val enablesBuff = cCards.any { KNOWN_CARD_MAP[it.card.cardId]?.isBoardBuff == true } &&
                !dpCards.any { KNOWN_CARD_MAP[it.card.cardId]?.isBoardBuff == true }
            if ((cScore > dpScore + 2.0 && coinCardsCost > noCoinCardsCost) || enablesBuff) {
                val cFmt = "%.1f".format(cScore)
                val extra = if (enablesBuff) " (出群体buff!)" else ""
                log.info { "硬币 得分${cFmt}${extra}" }
                coin.action.power()
                Thread.sleep((100..180).random().toLong())
                finalCards = cCards
            }
        }

        // 6. 排序出牌
        if (finalCards.isNotEmpty()) {
            DeckStrategyUtil.updateTextForCard(finalCards)
            val sorted = sortCards(finalCards, myMinionCount)
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
                    // 群体buff(希尔瓦娜斯的胜利)在随从<2时推迟
                    if (known?.isBoardBuff == true && myMinionCount < 2) {
                        log.info { "推迟buff: ${c.entityName.ifEmpty { c.cardId }} 场面仅${myMinionCount}随从" }
                        continue
                    }
                    val curCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    val needSpace = known?.needsSpace ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    if (curCnt + needSpace > 7) {
                        log.info { "格子满: ${c.entityName.ifEmpty { c.cardId }}" }
                        continue
                    }
                    if (known?.isDirectDamage == true && known.needsTargeting == true) {
                        // 击伤猎物：指向敌方随从
                        playCardWithTargeting(c, me, rival)
                    } else if (c.cardType === CardTypeEnum.SPELL && known?.isDirectDamage == true) {
                        // 直伤法术：打脸
                        val hero = rival.playArea.hero
                        if (hero != null) {
                            log.info { "直伤打脸: ${c.entityName.ifEmpty { c.cardId }}" }
                            c.action.power(hero)
                        } else {
                            c.action.power()
                        }
                    } else {
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
            log.info { "已打出时间线触发牌，跳过解场" }
            Thread.sleep((500..800).random().toLong())
            return
        }

        // 7. 解嘲讽（仅斩杀或接近斩杀时清理嘲讽）
        if (hasLethal && enemyTaunts.isNotEmpty()) {
            clearTauntsForLethal(me, enemyTaunts)
        }

        // 8. cleanPlay
        DeckStrategyUtil.cleanPlay()

        // 9. 兜底打脸（快攻核心）
        postCleanUpAttacks(me, rival)

        // 10. 地标激活（如有）
        DeckStrategyUtil.activeLocation(me.playArea.cards.toList())

        // 11. 贪婪填充：用尽剩余法力
        val curMinionCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val updatedFreeSpace = 7 - curMinionCnt
        var remaining = me.handArea.cards.toList()
            .filter { !it.isCoinCard && it.actualCost(me, enemyMinions) <= me.usableResource }
            .sortedByDescending { calcValue(it, me.usableResource, enemyMinions, curMinionCnt, updatedFreeSpace) }
        if (remaining.isNotEmpty() && me.usableResource > 0) {
            log.info { "贪婪填充: 剩${me.usableResource}费 ${remaining.size}张" }
            for (c in remaining) {
                val actualCost = c.actualCost(me, enemyMinions)
                if (me.usableResource >= actualCost) {
                    val curCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    val ns = KNOWN_CARD_MAP[c.cardId]?.needsSpace ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                        playCardWithTargeting(c, me, rival)
                    } else if (!me.playArea.isFull && curCnt + ns <= 7) {
                        playCardWithTargeting(c, me, rival)
                    }
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }
        // 第二遍：剩余≥3费时强制填充
        if (me.usableResource >= 3) {
            val curMinionCnt2 = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
            val updatedFreeSpace2 = 7 - curMinionCnt2
            remaining = me.handArea.cards.toList()
                .filter { !it.isCoinCard && it.actualCost(me, enemyMinions) <= me.usableResource }
                .sortedByDescending { calcValue(it, me.usableResource, enemyMinions, curMinionCnt2, updatedFreeSpace2) }
            if (remaining.isNotEmpty()) {
                log.info { "强制填充(≥3费): 剩${me.usableResource}费 ${remaining.size}张" }
                for (c in remaining) {
                    val actualCost = c.actualCost(me, enemyMinions)
                    if (me.usableResource >= actualCost) {
                        val curCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                        val ns = KNOWN_CARD_MAP[c.cardId]?.needsSpace ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                        if (c.cardType === CardTypeEnum.SPELL || c.cardType === CardTypeEnum.HERO) {
                            playCardWithTargeting(c, me, rival)
                        } else if (!me.playArea.isFull && curCnt + ns <= 7) {
                            playCardWithTargeting(c, me, rival)
                        }
                        Thread.sleep((80..150).random().toLong())
                    }
                }
            }
        }

        // 12. 英雄技能：快攻猎HP是稳固射击(2伤打脸)，有费就咬
        heroPower?.let { p ->
            if (me.usableResource >= p.cost) {
                // 快攻猎核心：有费就射脸
                val hasBetterPlay = remaining.isNotEmpty() &&
                    remaining.any { it.actualCost(me, enemyMinions) <= me.usableResource }
                if (!hasBetterPlay || me.usableResource >= p.cost + 1) {
                    log.info { "英雄技能(稳固射击)" }
                    p.action.power()
                    Thread.sleep((100..200).random().toLong())
                }
            }
        }

        // 13. 激发
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
        val hasTaunt = rival.playArea.cards.any { it.isEnemyTauntLike() }
        // 还要算上手牌直伤
        val handDirectDmg = me.handArea.cards
            .filter { KNOWN_CARD_MAP[it.cardId]?.isDirectDamage == true && it.actualCost(me, emptyList()) <= me.usableResource }
            .sumOf { KNOWN_CARD_MAP[it.cardId]?.directDamageValue ?: 0 }
        val heroPowerDmg = if (me.usableResource >= 2 && me.playArea.power != null) 2 else 0
        val totalReach = myAtk + handDirectDmg + heroPowerDmg
        return totalReach >= rivalHp && !hasTaunt
    }

    private fun isNearLethal(me: Player, rival: Player): Boolean {
        val myAtk = me.playArea.cards
            .filter { it.atc > 0 && (it.cardType == CardTypeEnum.MINION || it.cardType == CardTypeEnum.HERO) }
            .sumOf { it.atc }
        val hero = rival.playArea.hero ?: return false
        val rivalHp = hero.health + hero.armor - hero.damage
        val hasTaunt = rival.playArea.cards.any { it.isEnemyTauntLike() }
        return myAtk >= rivalHp * 0.7 && !hasTaunt
    }

    // ==================== 嘲讽检测 ====================

    private fun Card.isEnemyTauntLike(): Boolean {
        return isTaunt || cardType == CardTypeEnum.INVALID || KNOWN_CARD_MAP[cardId]?.isTaunt == true
    }

    // ==================== 清理嘲讽 ====================

    private fun clearTauntsForLethal(me: Player, taunts: List<Card>) {
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        if (myMinions.isEmpty()) return

        for (taunt in taunts.sortedBy { it.health }) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            // 最小代价解嘲讽：用刚好能解掉的随从
            val best = attackers
                .filter { it.atc >= taunt.health }
                .minByOrNull { it.atc * it.health }
                ?: attackers.maxByOrNull { it.atc }  // 解不掉就最大攻撞

            if (best != null && !best.isExhausted) {
                log.info { "解嘲讽: ${best.entityName}(${best.atc}/${best.health})→${taunt.entityName}(${taunt.atc}/${taunt.health})" }
                best.action.attack(taunt)
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
    ): Pair<Double, List<SimulateWeightCard>> {
        if (cards.isEmpty() || mana <= 0) return Pair(0.0, emptyList())
        val me = WAR.me
        val n = cards.size
        val vals = DoubleArray(n) { i ->
            calcValue(cards[i], mana, enemies, myMinionCount, freeSpace)
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
    ): Double {
        val known = KNOWN_CARD_MAP[c.cardId]
        var v = 0.5

        if (known != null) v += known.bonus

        // 直伤价值：快攻猎核心
        if (known?.isDirectDamage == true) {
            val dmg = known.directDamageValue
            v += dmg * 2.5  // 每点直伤=2.5价值（可打脸斩杀）
            val rivalHpVal = rivalHealthPercent()
            // 接近斩杀时直伤更珍贵
            if (rivalHpVal <= 0.5) v += dmg * 1.5
            else if (rivalHpVal <= 0.3) v += dmg * 3.0
        }

        // 身材效率（快攻猎偏好高攻低费）
        if (c.cost > 0 && c.cardType == CardTypeEnum.MINION) {
            // 攻击力权重高于血量（快攻猎要的是输出）
            v += (c.atc * 0.5 + c.health * 0.2) / c.cost
            if (c.atc >= 3) v += c.atc * 0.15  // 高攻随从加分
        }

        // 群体buff：希尔瓦娜斯的胜利+2攻，直面托维尔+1/+1
        if (known?.isBoardBuff == true) {
            val atkGain = known.buffAttack * myMinionCount
            v += atkGain * 2.0  // 攻击增量=直接打脸伤害
            if (myMinionCount >= 4) v += 3.0
            else if (myMinionCount >= 3) v += 1.5
            else if (myMinionCount >= 2) v += 0.5
            // 随从少时buff价值低
            if (myMinionCount <= 1) v -= 4.0
        }

        // 铺场token
        if (known?.isTokenGenerator == true) {
            val tokens = known.tokensGenerated.coerceAtMost(freeSpace)
            v += tokens * 1.8
            if (myMinionCount >= 2 && c.cardId == "CATA_560") v += 2.0  // 直面托维尔有场面时更强
        }

        // 过牌/发现
        if (known?.isDraw == true) {
            val handSize = WAR.me.handArea.cards.size
            v += known.drawCount * 1.0
            if (handSize <= 3) v += 1.5  // 手牌少时过牌迫切
            if (handSize >= 7) v -= 2.0  // 防爆牌
        }

        // 费用适配
        val actualCost = c.actualCost(WAR.me, enemies)
        if (actualCost > 0 && actualCost <= mana) {
            v += actualCost.toDouble() / mana.coerceAtLeast(1) * 0.6
        }

        // 冻结效果价值
        if (known?.isFreeze == true && enemies.isNotEmpty()) {
            v += 0.8  // 冻结可冻结威胁随从或嘲讽
        }

        // 关键词价值
        if (c.cardType == CardTypeEnum.MINION) {
            if (c.isCharge) v += 3.0  // 冲锋=直接伤害
            if (c.isRush) { v += 1.5; if (enemies.isNotEmpty()) v += 0.5 }
            if (c.isDivineShield) v += 0.5  // 圣盾=更难解
        }

        CARD_DATA_TRIE[c.cardId]?.let { cardData ->
            v += cardData.weight * 0.1
        }

        return v
    }

    private fun rivalHealthPercent(): Double {
        val hero = WAR.rival.playArea.hero ?: return 1.0
        val maxHp = hero.health + hero.armor
        if (maxHp <= 0) return 1.0
        return (maxHp - hero.damage).toDouble() / maxHp
    }

    // ==================== 卡牌实际费用 ====================

    private fun Card.actualCost(me: Player, enemies: List<Card>): Int {
        return this.cost
    }

    // ==================== 排序 ====================

    private fun sortCards(
        cards: List<SimulateWeightCard>,
        myMinionCount: Int,
    ): List<SimulateWeightCard> {
        val cardIds = cards.map { it.card.cardId }.toSet()
        return cards.sortedBy { swc ->
            val c = swc.card
            val known = KNOWN_CARD_MAP[c.cardId]
            when {
                // 1. 时间之沙（触发时间线）最先
                known?.triggersTimeline == true -> -10
                // 2. 0费牌
                c.cost == 0 -> -5
                // 3. 1费随从（抢先铺场）
                c.cost == 1 && c.cardType == CardTypeEnum.MINION -> 0
                // 4. 1费过牌（追踪术）
                c.cost == 1 && known?.isDraw == true -> 5
                // 5. 2费随从
                c.cost == 2 && c.cardType == CardTypeEnum.MINION -> 10
                // 6. 群体buff（随从足够时先出）
                known?.isBoardBuff == true -> when {
                    myMinionCount >= 4 -> 8
                    myMinionCount >= 2 -> 15
                    else -> 60
                }
                // 7. 直伤法术（后出，留作斩杀）
                known?.isDirectDamage == true -> 40
                // 8. 3费随从（三姐妹协同：奥蕾莉亚先于希尔瓦娜斯，双战吼AOE）
                c.cost == 3 && c.cardType == CardTypeEnum.MINION -> when (c.cardId) {
                    "TIME_609t1" -> 16  // 奥蕾莉亚：战吼触发两次，最优先
                    "TIME_609" -> if (cardIds.contains("TIME_609t1")) 22 else 19  // 希尔瓦娜斯：有奥蕾莉亚时稍后
                    "TIME_609t2" -> if (cardIds.contains("TIME_609t1")) 22 else 20  // 温蕾萨：有奥蕾莉亚时配合
                    else -> 20
                }
                // 9. 其他随从
                c.cardType == CardTypeEnum.MINION -> 25
                // 10. 其他法术
                c.cardType == CardTypeEnum.SPELL -> 30
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
            parts.add("[${i}]${c.entityName}(${c.atc}/${c.health})${c.cost}费=${sf} eid=${c.entityId}")
            if (s > bestS) { bestS = s; bestI = i }
        }
        log.info { "发现: ${parts.joinToString(" | ")} → 选[${bestI}] eid=${cards[bestI].entityId} cardId=${cards[bestI].cardId}" }
        return bestI
    }

    private fun scoreDiscover(c: Card, me: Player): Double {
        val known = KNOWN_CARD_MAP[c.cardId]
        var s = 0.5

        // 爆牌预防
        val handSize = me.handArea.cards.size
        if (handSize >= 9) {
            if (c.cost <= 1) s += 2.0 else if (c.cost >= 4) s -= 3.0
        } else if (handSize >= 7) {
            if (c.cost <= 2) s += 1.0 else if (c.cost >= 5) s -= 2.0
        }

        if (known != null) s += known.bonus * 0.5

        // 身材效率
        if (c.cost > 0 && c.cardType == CardTypeEnum.MINION) {
            s += (c.atc * 0.5 + c.health * 0.2) / c.cost
        }

        // 费用匹配
        if (c.cost <= me.usableResource) s += 0.3
        else if (c.cost > me.usableResource + 3) s -= 0.4

        // 快攻猎偏好：直伤 > 低费随从 > buff > 高费
        if (known?.isDirectDamage == true) s += known.directDamageValue * 0.6
        if (known?.isFaceMinion == true) s += 0.8
        if (c.cost in 1..2 && c.cardType == CardTypeEnum.MINION) s += 0.6
        if (known?.isBoardBuff == true) {
            val myCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
            if (myCnt >= 3) s += 1.5 else if (myCnt >= 1) s += 0.5
        }
        if (c.cost >= 5) s -= 1.5  // 快攻猎不要高费

        // 关键词
        if (c.isCharge) s += 1.5
        if (c.isRush && WAR.rival.playArea.cards.any { it.cardType == CardTypeEnum.MINION }) s += 0.5
        if (c.isDivineShield) s += 0.3

        CARD_DATA_TRIE[c.cardId]?.let { cd -> s += cd.weight * 0.1 }
        return s
    }

    // ==================== 回溯（时间线） ====================

    override fun execChooseTimeLine(timeLineEvent: TimelineEvent) {
        val me = WAR.me
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val rival = WAR.rival
        val enemyMinions = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val myAtk = me.playArea.cards
            .filter { it.atc > 0 && it.cardType == CardTypeEnum.MINION }
            .sumOf { it.atc }

        // 快攻猎时间线评估：场面攻击力高 → 维持；攻击力低/对面场面大 → 回溯
        val score = scoreBoard(me, myMinionCount, myAtk, enemyMinions)
        val threshold = if (myAtk >= 8) 0.35 else 0.55
        log.info { "时间线评分=${"%.2f".format(score)} 阈值=$threshold 场攻$myAtk 随从$myMinionCount → ${if (score >= threshold) "维持" else "回溯"}" }
        if (score >= threshold) timeLineEvent.keep() else timeLineEvent.rewind()
    }

    private fun scoreBoard(me: Player, myMinionCount: Int, myAtk: Int, enemyMinions: List<Card>): Double {
        var s = 0.5
        // 场面攻击力
        s += (myAtk - 4).coerceIn(-3, 8) * 0.05
        // 手牌资源
        s += (me.handArea.cards.size - 3).coerceIn(-3, 3) * 0.04
        // 敌方压力
        val enemyAtk = enemyMinions.sumOf { it.atc }
        s -= (enemyMinions.size - 1).coerceAtLeast(0) * 0.06
        if (enemyAtk >= 8) s -= 0.1
        // 血量
        val hero = me.playArea.hero ?: return s
        val maxHp = hero.health + hero.armor
        val curHp = maxHp - hero.damage
        s += (curHp.toDouble() / maxHp - 0.5) * 0.3
        return s.coerceIn(0.0, 1.0)
    }

    // ==================== 出牌辅助 ====================

    private fun playCardWithTargeting(c: Card, me: Player, rival: Player) {
        val known = KNOWN_CARD_MAP[c.cardId]
        val cardInfo = CARD_DATA_TRIE[c.cardId]

        // 指向性卡牌
        if (known?.needsTargeting == true) {
            val targets = if (known.targetsEnemy) {
                rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByRivalSpells() }
            } else {
                me.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByMySpells() }
            }
            if (targets.isNotEmpty()) {
                val target = if (known.targetsEnemy) {
                    // 击伤猎物：优先选低血可斩杀的敌方随从
                    targets.filter { it.health <= 1 || it.atc >= 3 }
                        .minByOrNull { it.health }
                        ?: targets.minByOrNull { it.health }
                } else {
                    targets.maxByOrNull { it.atc }
                }
                if (target != null) {
                    log.info { "指向出牌: ${c.entityName}→${target.entityName}(${target.atc}/${target.health})" }
                    c.action.power(target)
                    return
                }
            }
            log.info { "指向出牌(${c.entityName})无目标，跳过" }
            return
        }

        // 普通出牌
        c.action.autoPower(cardInfo)
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

        // 有嘲讽先解嘲讽
        if (enemyTaunts.isNotEmpty()) {
            for (taunt in enemyTaunts.sortedBy { it.health }) {
                val attacker = unchecked
                    .filter { !it.isExhausted && it.atc > 0 && it.atc >= taunt.health }
                    .minByOrNull { it.atc * it.health }
                    ?: unchecked
                        .filter { !it.isExhausted && it.atc > 0 }
                        .maxByOrNull { it.atc }
                if (attacker != null && !attacker.isExhausted) {
                    log.info { "解嘲讽: ${attacker.entityName}→${taunt.entityName}" }
                    attacker.action.attack(taunt)
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }

        // 打脸！（快攻猎核心）
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
                        log.info { "打脸: ${m.entityName}(${m.atc}/${m.health})→敌方英雄" }
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
