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

// ==================== 已知卡牌覆盖（效果均已查证） ====================

private data class KnownCardInfo(
    // 基础属性覆盖（INVALID卡牌用）
    val cardType: CardTypeEnum? = null,
    val atc: Int = 0,
    val health: Int = 0,
    val cardRace: CardRaceEnum? = null,
    val isBattlecry: Boolean = false,
    val isTaunt: Boolean = false,
    val isRush: Boolean = false,
    val isDivineShield: Boolean = false,
    val isElusive: Boolean = false,
    val isDeathrattle: Boolean = false,
    val bonus: Double = 0.0,
    // 快攻猎专属
    val isDirectDamage: Boolean = false,       // 直伤法术/随从战吼
    val directDamageValue: Int = 0,            // 直伤数值
    val isFaceMinion: Boolean = false,          // 适合抢脸的随从
    val isDraw: Boolean = false,               // 过牌/发现
    val drawCount: Int = 0,                    // 过牌数量
    val drawUntilHandSize: Int = 0,            // 抽牌直到此手牌数(TIME_601)
    val isFreeze: Boolean = false,             // 冻结(冰川裂片)
    val givesStone: Boolean = false,           // 给打3石头(TLC_427)
    val stoneDamage: Int = 0,                  // 石头伤害(TLC_427=3)
    val isReplay1Cost: Boolean = false,        // 重放用过1费牌(CATA_560)
    val isSecondCopyUpgrade: Boolean = false,   // 第二张复制变AOE(CATA_557)
    val positionalBonus: Boolean = false,       // 手牌正中加伤(TIME_600)
    val positionalBonusDamage: Int = 0,         // 位置加伤后总伤害
    val isZeroHpEnabler: Boolean = false,       // 手牌≤3时英雄技能0费(TIME_606)
    val isSisterCard: Boolean = false,          // 三姐妹之一
    val sisterPriority: Int = 0,               // 打出顺序 1=奥蕾莉亚 2=温蕾萨 3=希尔瓦娜斯
    val needsTargeting: Boolean = false,        // 需要手动指向
    val targetsEnemy: Boolean = false,          // 指向敌方
    val triggersTimeline: Boolean = false,      // 触发时间线选择
    val isChooseOne: Boolean = false,          // 抉择牌
    val chooseOneIndex: Int = 0,               // 抉择默认选项(0或1)
    val needsSpace: Int? = null,               // 需要的格子数
)

private val KNOWN_CARD_MAP: Map<String, KnownCardInfo> = mapOf(
    // ===== 1费随从 (6张) =====
    "CORE_UNG_205" to KnownCardInfo(  // 冰川裂片 1费2/1 战吼：冻结一个敌人
        cardType = CardTypeEnum.MINION, atc = 2, health = 1,
        isBattlecry = true, isFreeze = true, isFaceMinion = true, bonus = 1.5),
    "TIME_606" to KnownCardInfo(  // 奎尔多雷造箭师 1费1/3 手牌≤3时英雄技能消耗=0
        cardType = CardTypeEnum.MINION, atc = 1, health = 3,
        isZeroHpEnabler = true, isFaceMinion = true, bonus = 3.0),
    "DINO_434" to KnownCardInfo(  // 迅猛龙巢护工 1费1/1 野兽 战吼给1费随从 亡语给1费法术
        cardType = CardTypeEnum.MINION, atc = 1, health = 1,
        cardRace = CardRaceEnum.PET, isBattlecry = true, isDeathrattle = true,
        isDraw = true, drawCount = 1, isFaceMinion = true, bonus = 2.0),
    "CATA_558" to KnownCardInfo(  // 进击的募援官 1费2/2 扰魔
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        isElusive = true, isFaceMinion = true, bonus = 2.5),
    "TLC_249" to KnownCardInfo(  // 炽烈烬火 1费2/1 元素 亡语：随机打2分配给敌人
        cardType = CardTypeEnum.MINION, atc = 2, health = 1,
        isDeathrattle = true, isDirectDamage = true, directDamageValue = 2,
        isFaceMinion = true, bonus = 2.5),
    // ===== 1费法术 (4张) =====
    "CORE_DS1_185" to KnownCardInfo(  // 奥术射击 1费 造成2点伤害
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 2, bonus = 2.0),
    "CORE_BAR_801" to KnownCardInfo(  // 击伤猎物 1费 造成1点伤害+召唤1/1突袭土狼
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 1,
        needsTargeting = true, targetsEnemy = true, bonus = 2.0),
    "CORE_DS1_184" to KnownCardInfo(  // 追踪术 1费 从牌库发现一张牌
        cardType = CardTypeEnum.SPELL, isDraw = true, drawCount = 1, bonus = 1.5),
    "TIME_EVENT_999" to KnownCardInfo(  // 时间之沙 1费 触发时间线选择
        cardType = CardTypeEnum.SPELL, triggersTimeline = true, bonus = 5.0),
    // ===== 2费随从 (2张) =====
    "TLC_427" to KnownCardInfo(  // 抛石鱼人 2费1/3 鱼人 战吼：获取1费打3石头
        cardType = CardTypeEnum.MINION, atc = 1, health = 3,
        cardRace = CardRaceEnum.UNKNOWN, isBattlecry = true,
        givesStone = true, stoneDamage = 3,
        isFaceMinion = true, bonus = 2.0),
    "TIME_601" to KnownCardInfo(  // 拾箭龙鹰 2费2/2 野兽 战吼：抽牌直到手牌=3
        cardType = CardTypeEnum.MINION, atc = 2, health = 2,
        cardRace = CardRaceEnum.PET, isBattlecry = true,
        isDraw = true, drawUntilHandSize = 3,
        isFaceMinion = true, bonus = 1.5),
    // ===== 2费法术 (2张) =====
    "CATA_557" to KnownCardInfo(  // 希尔瓦娜斯的胜利 2费 造成3点伤害；使用过复制则改为对所有敌人
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 3,
        isSecondCopyUpgrade = true, needsTargeting = true, targetsEnemy = true, bonus = 2.5),
    "TIME_600" to KnownCardInfo(  // 精确射击 2费 造成3点伤害（手牌正中则5点）
        cardType = CardTypeEnum.SPELL, isDirectDamage = true, directDamageValue = 3,
        positionalBonus = true, positionalBonusDamage = 5, bonus = 3.0),
    // ===== 3费随从 — 三姐妹(3张，希尔瓦娜斯卡牌本身只带1张，奇闻自动洗入t1/t2) =====
    "TIME_609" to KnownCardInfo(  // 游侠将军希尔瓦娜斯 3费2/4 战吼：AOE打2；每用过一姐妹重复一次
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, isDirectDamage = true, directDamageValue = 2,
        isSisterCard = true, sisterPriority = 3,
        isFaceMinion = true, bonus = 3.0),
    "TIME_609t1" to KnownCardInfo(  // 游侠队长奥蕾莉亚 3费2/4 战吼：发现法术；每用过一姐妹重复
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true, isDraw = true, drawCount = 1,
        isSisterCard = true, sisterPriority = 1,
        isFaceMinion = true, bonus = 2.5),
    "TIME_609t2" to KnownCardInfo(  // 游侠新兵温蕾萨 3费2/4 战吼：牌库随从+1/+1；每用过一姐妹重复
        cardType = CardTypeEnum.MINION, atc = 2, health = 4,
        isBattlecry = true,
        isSisterCard = true, sisterPriority = 2,
        isFaceMinion = true, bonus = 2.0),
    // ===== 3费法术 (1张) =====
    "CATA_560" to KnownCardInfo(  // 直面托维尔 3费 重放本局对战中使用过的所有1费牌
        cardType = CardTypeEnum.SPELL, isReplay1Cost = true, bonus = 4.0),
    // ===== 常见敌方嘲讽 =====
    "CORE_GVG_085" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 1, health = 2, isTaunt = true, isDivineShield = true),
    "CORE_BOT_911" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 1, health = 5, isTaunt = true),
    "CORE_EX1_048" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 3, health = 5, isTaunt = true),
    "CORE_ICC_807" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 1, health = 3, isTaunt = true),
    "CORE_DRG_237" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 2, health = 4, cardRace = CardRaceEnum.DRAGON, isTaunt = true),
    "CORE_OG_218" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 2, health = 6, isTaunt = true),
    "CORE_TRL_401" to KnownCardInfo(cardType = CardTypeEnum.MINION, atc = 5, health = 7, isTaunt = true, isRush = true),
    // ===== 外部生成牌（低价值标记） =====
    "CATA_136" to KnownCardInfo(cardType = CardTypeEnum.SPELL, bonus = -3.0),
    "CORE_LOOT_309" to KnownCardInfo(cardType = CardTypeEnum.SPELL, bonus = -3.0),
    "CORE_EX1_169" to KnownCardInfo(cardType = CardTypeEnum.SPELL, bonus = -3.0),
)

// ==================== 快攻猎-v1 策略主类 ====================

class FaceHunterDeck : DeckStrategy() {

    override fun name(): String = "快攻猎-v1"

    override fun description(): String =
        "快攻猎v1：0费射箭引擎(造箭师)+1费重放(直面托维尔)+三姐妹协同+直伤斩杀"

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
            // 奎尔多雷造箭师(0费射箭引擎)最高优先级
            if (card.cardId == "TIME_606") score += 1.0
            else score += 0.6
            if (known?.isFaceMinion == true) score += 0.2
            if (known?.isElusive == true) score += 0.1  // 扰魔难解
            if (known?.isDeathrattle == true) score += 0.1  // 亡语多价值
        }
        // 2费随从
        if (card.cost == 2 && card.cardType == CardTypeEnum.MINION) {
            score += 0.4
            if (isGoingFirst) score += 0.1
            // TIME_601在手牌少时好（加速抽牌）
            if (known?.drawUntilHandSize == 3) score += 0.1
        }
        // 直伤法术
        if (known?.isDirectDamage == true) {
            score += if (isGoingFirst) 0.2 else 0.35
            if (known.positionalBonus) score += 0.1  // 精确射击双重价值
        }
        // 追踪术/时间之沙
        if (card.cardId == "CORE_DS1_184") {
            val hasOneDrop = WAR.me.handArea.cards.any {
                it.cost == 1 && it.cardType == CardTypeEnum.MINION && it.cardId != card.cardId
            }
            score += if (hasOneDrop) 0.15 else 0.35  // 已有1费降低追踪术价值
        }
        if (card.cardId == "TIME_EVENT_999") score += 0.4
        // 3费牌（三姐妹/直面托维尔）
        if (card.cost == 3) {
            score += if (isGoingFirst) 0.1 else 0.25
            if (known?.isReplay1Cost == true) score -= 0.3  // 前期不留直面托维尔
        }
        // 高费惩罚
        if (card.cost >= 4) score -= 3.0
        // 身材效率
        if (card.cost > 0 && card.cardType == CardTypeEnum.MINION) {
            score += (card.atc + card.health).toDouble() / card.cost * 0.08
        }
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

        // 1. 场面评估
        val enemyMinions = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION }
        val enemyTaunts = enemyMinions.filter { it.isEnemyTauntLike() }
        val enemyAtk = enemyMinions.sumOf { it.atc }
        val myMinionCount = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
        val freeSpace = 7 - myMinionCount
        val handSize = me.handArea.cards.size
        log.info { "=== ${me.usableResource}费 手牌${handSize} 我方${myMinionCount}随从(空${freeSpace}格) 敌${enemyMinions.size}个(攻${enemyAtk}) ===" }

        // 1.5 先攻后铺：现有随从先打脸/解关键嘲讽（伤害前置）
        val existingMinions = me.playArea.cards.filter {
            it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted
        }
        if (existingMinions.isNotEmpty()) {
            if (enemyTaunts.isEmpty()) {
                val rivalHero = rival.playArea.hero
                if (rivalHero != null) {
                    for (m in existingMinions.sortedByDescending { it.atc }) {
                        log.info { "先攻打脸: ${m.entityName}(${m.atc}/${m.health})→敌方英雄" }
                        m.action.attack(rivalHero)
                        Thread.sleep((80..150).random().toLong())
                    }
                }
            } else {
                // 有嘲讽：用最弱能杀的随从解最弱嘲讽，腾出打脸通道
                val weakTaunt = enemyTaunts.minByOrNull { it.health }
                val killer = existingMinions
                    .filter { it.atc >= (weakTaunt?.health ?: 99) }
                    .minByOrNull { it.atc }
                    ?: existingMinions.maxByOrNull { it.atc } // 杀不掉也要削血
                if (killer != null && weakTaunt != null) {
                    log.info { "先攻解嘲讽: ${killer.entityName}(${killer.atc})→${weakTaunt.entityName}(${weakTaunt.health})" }
                    killer.action.attack(weakTaunt)
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }
        // 1.6 造箭师0费射箭：DP前使用（减手牌触发0费条件 + 伤害前置）
        val hasZeroHp = me.playArea.cards.any { it.cardId == "TIME_606" } && handSize <= 3
        var heroPowerUsed = false
        if (hasZeroHp && heroPower != null && heroPower.cost <= me.usableResource) {
            log.info { "造箭师在场+手牌${handSize}≤3 → 0费英雄技能(DP前)" }
            heroPower.action.power()
            heroPowerUsed = true
            Thread.sleep((100..200).random().toLong())
        }

        // 2. 斩杀检测
        val hasLethal = checkLethal(me, rival)
        if (hasLethal) log.info { "斩杀! 总伤害 ≥ 敌方血量" }

        // 3. 手牌处理
        val hands = me.handArea.cards.toList()
        val myCards = hands.toMutableList()
        myCards.removeAll { it.isCoinCard }

        // 4. 自定义DP
        val dpMana = me.usableResource
        val (dpScore, dpCards) = customDP(myCards, dpMana, enemyMinions, myMinionCount, freeSpace)
        val dpFmt = "%.1f".format(dpScore)
        log.info { "DP得分${dpFmt} 选中${dpCards.size}张" }

        var finalCards = dpCards

        // 5. 硬币评估
        val coin = DeckStrategyUtil.findCoin(hands)
        if (coin != null && me.usableResource <= 5) {
            val (cScore, cCards) = customDP(myCards, dpMana + 1, enemyMinions, myMinionCount, freeSpace)
            val coinCardsCost = cCards.sumOf { it.card.actualCost(me, enemyMinions) }
            val noCoinCardsCost = dpCards.sumOf { it.card.actualCost(me, enemyMinions) }
            if ((cScore > dpScore + 2.0 && coinCardsCost > noCoinCardsCost)
                || cCards.size > dpCards.size + 1
            ) {
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
            val sorted = sortCards(finalCards, myMinionCount, handSize)
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
                    val curCnt = me.playArea.cards.count { it.cardType == CardTypeEnum.MINION }
                    val needSpace = known?.needsSpace ?: (if (c.cardType == CardTypeEnum.MINION) 1 else 0)
                    if (curCnt + needSpace > 7) {
                        log.info { "格子满: ${c.entityName.ifEmpty { c.cardId }}" }
                        continue
                    }
                    // TIME_600 位置提示
                    if (known?.positionalBonus == true) {
                        val posInHand = me.handArea.cards.indexOf(c)
                        val totalHand = me.handArea.cards.size
                        val isMiddle = totalHand > 1 && posInHand == totalHand / 2
                        log.info { "精确射击 手牌位置${posInHand}/${totalHand} ${if (isMiddle) "正中(打5!)" else "非正中(打3)"}" }
                    }
                    playCardWithTargeting(c, me, rival)
                    used += c.actualCost(me, enemyMinions)
                    // 发现/过牌后等更久（发现UI需要时间选择）
                    val waitMs = if (known?.isDraw == true) (1200..2000).random().toLong()
                        else if (firstAction) (100..180).random().toLong()
                        else (80..150).random().toLong()
                    Thread.sleep(waitMs)
                    firstAction = false
                    // 冲锋/突袭随从立即攻击（打出后先攻再继续出牌）
                    if (c.cardType == CardTypeEnum.MINION && (c.isCharge || c.isRush)) {
                        val justPlayed = me.playArea.cards
                            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
                            .maxByOrNull { it.atc }
                        if (justPlayed != null) {
                            val nowTaunts = rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.isEnemyTauntLike() }
                            if (nowTaunts.isEmpty()) {
                                rival.playArea.hero?.let { h ->
                                    log.info { "冲锋打脸: ${justPlayed.entityName}(${justPlayed.atc})→敌方英雄" }
                                    justPlayed.action.attack(h)
                                    Thread.sleep((80..150).random().toLong())
                                }
                            } else {
                                val t = nowTaunts.minByOrNull { it.health }
                                if (t != null) {
                                    log.info { "突击解嘲: ${justPlayed.entityName}→${t.entityName}" }
                                    justPlayed.action.attack(t)
                                    Thread.sleep((80..150).random().toLong())
                                }
                            }
                        }
                    }
                    // 打出时间线触发牌后停止继续出牌（等时间线选择）
                    if (known?.triggersTimeline == true) {
                        log.info { "打出时间线牌，停止后续出牌" }
                        break
                    }
                }
            }
            log.info { "DP消耗${used}费 剩${me.usableResource}费" }
        } else {
            log.info { "DP未选中牌" }
        }

        // 6.5 出牌后打脸：所有随从（含刚打出的冲锋/突袭）打脸伤害最大化
        postCleanUpAttacks(me, rival)

        // 6.6 英雄技能：攻击后用剩余费用补伤害
        if (!heroPowerUsed && heroPower != null && me.usableResource >= heroPower.cost) {
            log.info { "英雄技能(稳固射击)" }
            heroPower.action.power()
            heroPowerUsed = true
            Thread.sleep((100..200).random().toLong())
        }

        // 6.7 时间线检测（攻击+射箭已前置完成，即使时间线回溯也不丢伤害）
        if (finalCards.any { KNOWN_CARD_MAP[it.card.cardId]?.triggersTimeline == true }) {
            log.info { "已打出时间线触发牌" }
            Thread.sleep((500..800).random().toLong())
            return
        }

        // 7. cleanPlay — 快攻猎只在有嘲讽时解场，无嘲讽优先打脸
        val hasRemainingTaunts = rival.playArea.cards.any {
            it.cardType == CardTypeEnum.MINION && !it.isExhausted && it.isEnemyTauntLike()
        }
        if (hasRemainingTaunts) {
            DeckStrategyUtil.cleanPlay()
        }

        // 8. 地标激活
        DeckStrategyUtil.activeLocation(me.playArea.cards.toList())

        // 9. 贪婪填充
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

        // 10. 填充后英雄技能（可能因造箭师+手牌减少而0费可用）
        if (!heroPowerUsed && heroPower != null && me.usableResource >= heroPower.cost) {
            log.info { "英雄技能(稳固射击-补)" }
            heroPower.action.power()
            heroPowerUsed = true
            Thread.sleep((100..200).random().toLong())
        }

        // 11. 激发
        me.playArea.cards.toList().forEach { c ->
            if (c.isLaunchpad && me.usableResource >= c.launchCost()) {
                c.action.launch()
                Thread.sleep((80..150).random().toLong())
            }
        }

        // 12. 回合结束防呆检查：随从未攻击→打脸、英雄技能可用→射箭
        safetyNetAttacks(me, rival)
        if (!heroPowerUsed && heroPower != null && me.usableResource >= heroPower.cost) {
            log.info { "防呆英雄技能(稳固射击-回合结束)" }
            heroPower.action.power()
            Thread.sleep((100..200).random().toLong())
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
        val handDirectDmg = me.handArea.cards
            .filter { KNOWN_CARD_MAP[it.cardId]?.isDirectDamage == true && it.actualCost(me, emptyList()) <= me.usableResource }
            .sumOf { KNOWN_CARD_MAP[it.cardId]?.directDamageValue ?: 0 }
        val heroPowerCost = me.playArea.power?.cost ?: 2
        val heroPowerDmg = if (me.usableResource >= heroPowerCost && me.playArea.power != null) 2 else 0
        return myAtk + handDirectDmg + heroPowerDmg >= rivalHp && !hasTaunt
    }

    // ==================== 嘲讽检测 ====================

    private fun Card.isEnemyTauntLike(): Boolean {
        return isTaunt || KNOWN_CARD_MAP[cardId]?.isTaunt == true
    }

    // ==================== 清理嘲讽 ====================

    private fun clearTauntsForLethal(me: Player, taunts: List<Card>) {
        val myMinions = me.playArea.cards
            .filter { it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted }
        if (myMinions.isEmpty()) return
        for (taunt in taunts.sortedBy { it.health }) {
            val attackers = myMinions.filter { !it.isExhausted && it.atc > 0 }
            val best = attackers
                .filter { it.atc >= taunt.health }
                .minByOrNull { it.atc * it.health }
                ?: attackers.maxByOrNull { it.atc }
            if (best != null && !best.isExhausted) {
                log.info { "解嘲讽: ${best.entityName}(${best.atc}/${best.health})→${taunt.entityName}" }
                best.action.attack(taunt)
                Thread.sleep((80..150).random().toLong())
            }
        }
    }

    // ==================== 自定义DP ====================

    private fun customDP(
        cards: List<Card>, mana: Int, enemies: List<Card>,
        myMinionCount: Int, freeSpace: Int,
    ): Pair<Double, List<SimulateWeightCard>> {
        if (cards.isEmpty() || mana <= 0) return Pair(0.0, emptyList())
        val me = WAR.me
        val n = cards.size
        val vals = DoubleArray(n) { i -> calcValue(cards[i], mana, enemies, myMinionCount, freeSpace) }
        val costs = IntArray(n) { i -> cards[i].actualCost(me, enemies) }
        val dp = DoubleArray(mana + 1)
        val keep = Array(n) { BooleanArray(mana + 1) }
        for (i in 0 until n) {
            val c = costs[i]
            if (c > mana) continue
            for (j in mana downTo c) {
                val nv = dp[j - c] + vals[i]
                if (nv > dp[j]) { dp[j] = nv; keep[i][j] = true }
            }
        }
        val sel = mutableListOf<SimulateWeightCard>()
        var j = mana
        for (i in n - 1 downTo 0) {
            if (keep[i][j]) { sel.add(SimulateWeightCard(cards[i], vals[i], 0.0)); j -= costs[i] }
        }
        return Pair(dp[mana], sel.reversed())
    }

    // ==================== 卡牌价值 ====================

    private fun calcValue(
        c: Card, mana: Int, enemies: List<Card>,
        myMinionCount: Int = 0, freeSpace: Int = 7,
    ): Double {
        val known = KNOWN_CARD_MAP[c.cardId]
        val handSize = WAR.me.handArea.cards.size
        var v = 0.5

        // INVALID惩罚：行为解析失败一律降分（cardId在KNOWN中也不放过）
        if (c.cardType == CardTypeEnum.INVALID) {
            return -5.0
        }

        if (known != null) v += known.bonus

        // 直伤价值（奥术射击/精确射击/希尔瓦娜斯胜利/炽烈烬火亡语/击伤猎物）
        if (known?.isDirectDamage == true) {
            val dmg = known.directDamageValue
            // 基础：每点直伤价值2.5
            v += dmg * 2.5
            // 精确射击位置加成
            if (known.positionalBonus) {
                val posInHand = WAR.me.handArea.cards.indexOf(c)
                val totalHand = handSize
                val isMiddle = totalHand > 1 && posInHand == totalHand / 2
                if (isMiddle) v += 2.0  // 打5比打3多2价值
            }
            // CATA_557第二张：AOE全体敌人（价值更高）
            if (known.isSecondCopyUpgrade) {
                val graveCount = WAR.me.graveyardArea?.cards?.count { it.cardId == "CATA_557" } ?: 0
                if (graveCount >= 1 && enemies.isNotEmpty()) {
                    v += enemies.size * 2.0  // 打全体=AOE
                }
            }
            // 接近斩杀时直伤加分
            val rivalHpVal = rivalHealthPercent()
            if (rivalHpVal <= 0.5) v += dmg * 1.0
            if (rivalHpVal <= 0.3) v += dmg * 2.0
        }

        // 身材效率（快攻猎偏好高攻低费）
        if (c.cost > 0 && c.cardType == CardTypeEnum.MINION) {
            v += (c.atc * 0.5 + c.health * 0.2) / c.cost
            if (c.atc >= 3) v += c.atc * 0.15
            if (known?.isElusive == true) v += 0.5  // 扰魔=更难解
        }

        // 0费射箭引擎（TIME_606在场+手牌≤3）
        if (known?.isZeroHpEnabler == true) {
            val hasZeroHpAlready = WAR.me.playArea.cards.any { it.cardId == "TIME_606" }
            if (!hasZeroHpAlready) {
                v += 2.0  // 第一张高价值
                if (handSize <= 3) v += 1.5  // 立即触发0费射箭
            } else {
                v -= 2.0  // 第二张价值低
            }
        }

        // 重放1费牌（CATA_560）：价值随已打出+手牌中可打的1费牌数量增长
        if (known?.isReplay1Cost == true) {
            val grave = WAR.me.graveyardArea
            val played1CostCount = grave?.cards?.count { it.cost == 1 && it.cardType == CardTypeEnum.MINION } ?: 0
                + (grave?.cards?.count { it.cost == 1 && it.cardType == CardTypeEnum.SPELL } ?: 0)
            // 手牌中可打出的1费牌（托维尔前先投资）
            val hand1CostPlayable = WAR.me.handArea.cards.count {
                it.cost == 1 && it.cardId != c.cardId && it.actualCost(WAR.me, enemies) <= mana
            }
            val total1Cost = played1CostCount + hand1CostPlayable
            v += total1Cost * 1.5  // 每张1费牌+1.5
            if (total1Cost >= 4) v += 3.0
            else if (total1Cost >= 2) v += 1.0
            if (played1CostCount < 2 && hand1CostPlayable < 1) v -= 3.0
        }

        // 给石头（TLC_427）：1费打3石头=直伤价值
        if (known?.givesStone == true) {
            v += (known.stoneDamage * 2.5) * 0.7  // 石头需要额外1费打出，折价
        }

        // 过牌/发现
        if (known?.isDraw == true) {
            if (known.drawUntilHandSize > 0) {
                // TIME_601：手牌越小价值越高
                val drawsNeeded = (known.drawUntilHandSize - handSize).coerceAtLeast(0)
                v += drawsNeeded * 1.5
                if (handSize <= 1) v += 2.0
                if (handSize >= 4) v -= 3.0  // 手牌≥4时抽不到牌
            } else {
                v += known.drawCount * 0.8
                if (handSize <= 3) v += 1.0
            }
            if (handSize >= 8) v -= 3.0  // 防爆牌
        }

        // 冻结
        if (known?.isFreeze == true && enemies.isNotEmpty()) v += 0.8

        // 亡语直伤（炽烈烬火）
        if (known?.isDeathrattle == true && known.isDirectDamage) v += 0.5

        // 关键词
        if (c.cardType == CardTypeEnum.MINION) {
            if (c.isCharge) v += 3.0
            if (c.isRush) { v += 1.5; if (enemies.isNotEmpty()) v += 0.5 }
            if (c.isDivineShield) v += 0.5
        }

        // 费用适配
        val actualCost = c.actualCost(WAR.me, enemies)
        if (actualCost > 0 && actualCost <= mana) {
            v += actualCost.toDouble() / mana.coerceAtLeast(1) * 0.5
        }

        CARD_DATA_TRIE[c.cardId]?.let { v += it.weight * 0.1 }
        return v
    }

    private fun rivalHealthPercent(): Double {
        val hero = WAR.rival.playArea.hero ?: return 1.0
        val maxHp = hero.health + hero.armor
        if (maxHp <= 0) return 1.0
        return (maxHp - hero.damage).toDouble() / maxHp
    }

    // ==================== 卡牌实际费用 ====================

    private fun Card.actualCost(me: Player, enemies: List<Card>): Int = this.cost

    // ==================== 排序 ====================

    private fun sortCards(
        cards: List<SimulateWeightCard>,
        myMinionCount: Int,
        handSize: Int,
    ): List<SimulateWeightCard> {
        val cardIds = cards.map { it.card.cardId }.toSet()
        val sisInHand = cardIds.filter { KNOWN_CARD_MAP[it]?.isSisterCard == true }.toSet()
        return cards.sortedBy { swc ->
            val c = swc.card
            val known = KNOWN_CARD_MAP[c.cardId]
            when {
                // 1. 时间之沙（最后出牌，避免break阻断后续出牌）
                known?.triggersTimeline == true -> 40
                // 2. 0费牌
                c.cost == 0 -> -5
                // 3. 1费随从（抢先铺场）
                c.cost == 1 && c.cardType == CardTypeEnum.MINION -> 0
                // 4. 1费法术（铺场后出）
                c.cost == 1 && c.cardType == CardTypeEnum.SPELL -> 5
                // 5. 2费随从
                c.cost == 2 && c.cardType == CardTypeEnum.MINION -> 10
                // 6. 2费法术（直伤后出）
                c.cost == 2 && c.cardType == CardTypeEnum.SPELL -> 15
                // 7. 三姐妹协同：t1(奥蕾莉亚)→t2(温蕾萨)→609(希尔瓦娜斯)
                known?.isSisterCard == true -> {
                    when (c.cardId) {
                        "TIME_609t1" -> 18  // 奥蕾莉亚：最先发现法术
                        "TIME_609t2" -> 20  // 温蕾萨：第二buff牌库
                        "TIME_609" -> {
                            // 希尔瓦娜斯：有姐妹则AOE重复更多
                            val sisCount = sisInHand.count {
                                it == "TIME_609t1" || it == "TIME_609t2"
                            }
                            22 - sisCount  // 姐妹越多越早出（更多AOE重复）
                        }
                        else -> 20
                    }
                }
                // 8. 直面托维尔（费高后出）
                known?.isReplay1Cost == true -> 35
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
        val handSize = me.handArea.cards.size
        var s = 0.5

        // 爆牌预防
        if (handSize >= 9) {
            if (c.cost <= 1) s += 2.0 else if (c.cost >= 4) s -= 3.0
        } else if (handSize >= 7) {
            if (c.cost <= 2) s += 1.0 else if (c.cost >= 5) s -= 2.0
        }

        // 已知牌bonus
        if (known != null) s += known.bonus * 0.5

        // INVALID惩罚
        if (c.cardType == CardTypeEnum.INVALID && known == null) s -= 3.0

        // 身材效率
        if (c.cost > 0 && c.cardType == CardTypeEnum.MINION) {
            s += (c.atc * 0.5 + c.health * 0.2) / c.cost
        }

        // 费用匹配
        if (c.cost <= me.usableResource) s += 0.3
        else if (c.cost > me.usableResource + 3) s -= 0.4

        // 快攻猎偏好
        if (known?.isDirectDamage == true) s += known.directDamageValue * 0.6
        if (known?.isFaceMinion == true) s += 0.8
        if (known?.isZeroHpEnabler == true && !me.playArea.cards.any { it.cardId == "TIME_606" }) s += 1.5
        if (known?.isReplay1Cost == true) s += 1.0
        if (c.cost in 1..2 && c.cardType == CardTypeEnum.MINION) s += 0.6
        if (c.cost >= 5) s -= 1.5

        // 关键词
        if (c.isCharge) s += 1.5
        if (c.isRush && WAR.rival.playArea.cards.any { it.cardType == CardTypeEnum.MINION }) s += 0.5
        if (c.isDivineShield) s += 0.3
        if (known?.isElusive == true) s += 0.3

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

        val score = scoreBoard(me, myMinionCount, myAtk, enemyMinions)
        val threshold = if (myAtk >= 8) 0.35 else 0.55
        val shouldKeep = score >= threshold
        log.info { "时间线评分=${"%.2f".format(score)} 阈值=$threshold 场攻$myAtk → ${if (shouldKeep) "维持" else "回溯"}" }
        // 反射直接点击时间线按钮（绕过GameUtil.keepTimeline()/rewindTimeline()的lClick(true)取消bug）
        if (!timelineClickFixed(shouldKeep)) {
            // 反射失败兜底：标准SDK路径
            if (shouldKeep) timeLineEvent.keep() else timeLineEvent.rewind()
        }
    }

    private fun scoreBoard(me: Player, myMinionCount: Int, myAtk: Int, enemyMinions: List<Card>): Double {
        var s = 0.5
        s += (myAtk - 4).coerceIn(-3, 8) * 0.05
        s += (me.handArea.cards.size - 3).coerceIn(-3, 3) * 0.04
        val enemyAtk = enemyMinions.sumOf { it.atc }
        s -= (enemyMinions.size - 1).coerceAtLeast(0) * 0.06
        if (enemyAtk >= 8) s -= 0.1
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

        // CATA_557第二张→AOE全体敌人，无需指向
        if (known?.isSecondCopyUpgrade == true) {
            val graveCount = WAR.me.graveyardArea?.cards?.count { it.cardId == c.cardId } ?: 0
            if (graveCount >= 1) {
                log.info { "AOE直伤(第二张): ${c.entityName.ifEmpty { c.cardId }} → 全体敌人" }
                c.action.power()
                return
            }
        }

        // 指向性卡牌：手动选目标（快攻猎直伤优先打脸）
        if (known?.needsTargeting == true) {
            // 直伤牌：优先打脸，除非有嘲讽需解
            if (known.isDirectDamage) {
                val taunts = rival.playArea.cards.filter {
                    it.cardType == CardTypeEnum.MINION && it.isEnemyTauntLike()
                        && it.canBeTargetedByRivalSpells()
                }
                if (taunts.isEmpty()) {
                    rival.playArea.hero?.let { h ->
                        log.info { "直伤打脸: ${c.entityName.ifEmpty { c.cardId }}" }
                        c.action.power(h)
                        return
                    }
                } else {
                    // 有嘲讽→直伤解最弱嘲讽为随从清路
                    val best = taunts.filter { it.health <= known.directDamageValue }
                        .minByOrNull { it.health } ?: taunts.minByOrNull { it.health }
                    if (best != null) {
                        log.info { "直伤解嘲讽: ${c.entityName.ifEmpty { c.cardId }}→${best.entityName}" }
                        c.action.power(best)
                        return
                    }
                }
            }
            val targets = if (known.targetsEnemy) {
                rival.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByRivalSpells() }
            } else {
                me.playArea.cards.filter { it.cardType == CardTypeEnum.MINION && it.canBeTargetedByMySpells() }
            }
            if (targets.isNotEmpty()) {
                val target = if (known.targetsEnemy) {
                    targets.filter { it.health <= 1 || it.atc >= 3 }
                        .minByOrNull { it.health }
                        ?: targets.minByOrNull { it.health }
                } else {
                    targets.maxByOrNull { it.atc }
                }
                if (target != null) {
                    log.info { "指向出牌: ${c.entityName.ifEmpty { c.cardId }}→${target.entityName}" }
                    c.action.power(target)
                    return
                }
            }
            log.info { "指向出牌(${c.entityName.ifEmpty { c.cardId }})无目标，跳过" }
            return
        }

        // 直伤法术兜底：指向敌方英雄
        if (known?.isDirectDamage == true && c.cardType == CardTypeEnum.SPELL) {
            val rivalHero = rival.playArea.hero
            if (rivalHero != null) {
                log.info { "直伤打脸: ${c.entityName.ifEmpty { c.cardId }}" }
                c.action.power(rivalHero)
                return
            }
        }

        // 抉择牌：先打出触发抉择UI，反射点击选项（绕过SDK的lClick(true)取消bug）
        if (known?.isChooseOne == true) {
            log.info { "抉择牌: ${c.entityName.ifEmpty { c.cardId }} 选[${known.chooseOneIndex}]" }
            safePower(c)
            Thread.sleep((500..800).random().toLong())
            chooseOneFixed(known.chooseOneIndex)
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

        // 有嘲讽先解
        if (enemyTaunts.isNotEmpty()) {
            for (taunt in enemyTaunts.sortedBy { it.health }) {
                val attacker = unchecked
                    .filter { !it.isExhausted && it.atc > 0 && it.atc >= taunt.health }
                    .minByOrNull { it.atc * it.health }
                    ?: unchecked.filter { !it.isExhausted && it.atc > 0 }.maxByOrNull { it.atc }
                if (attacker != null && !attacker.isExhausted) {
                    log.info { "解嘲讽: ${attacker.entityName}→${taunt.entityName}" }
                    attacker.action.attack(taunt)
                    Thread.sleep((80..150).random().toLong())
                }
            }
        }

        // 打脸
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

    // ==================== 抉择/回溯牌反射点击（绕过SDK的lClick(true)取消bug） ====================

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

    /**
     * 直接点击时间线按钮（维持/回溯），绕过GameUtil.keepTimeline()/rewindTimeline()的lClick(true)取消问题
     * @param keep true=维持时间线[1], false=回溯时间线[0]
     */
    private fun timelineClickFixed(keep: Boolean): Boolean {
        return try {
            val gameUtilClass = Class.forName("club.xiaojiawei.hsscript.utils.GameUtil")
            val instanceField = gameUtilClass.getDeclaredField("INSTANCE")
            val instance = instanceField.get(null)
            val rectsField = gameUtilClass.getDeclaredField("TIMELINE_RECTS")
            rectsField.isAccessible = true
            val rects = rectsField.get(instance) as Array<*>
            val index = if (keep) 1 else 0  // [0]=回溯, [1]=维持
            val rect = rects[index] ?: return false
            val rectClass = rect.javaClass
            val valid = rectClass.getMethod("isValid").invoke(rect) as Boolean
            if (valid) {
                rectClass.getMethod("lClick", java.lang.Boolean.TYPE).invoke(rect, false)
                true
            } else false
        } catch (e: Exception) {
            log.warn { "timelineClickFixed反射失败: ${e.message}" }
            false
        }
    }

    // ==================== 防呆机制 ====================

    /**
     * 兜底攻击：遍历所有未攻击随从打脸（防随从攻击伏笔）
     */
    private fun safetyNetAttacks(me: Player, rival: Player) {
        val unchecked = me.playArea.cards.filter {
            it.cardType == CardTypeEnum.MINION && it.atc > 0 && !it.isExhausted
        }
        if (unchecked.isEmpty()) return
        val hasTaunt = rival.playArea.cards.any {
            it.cardType == CardTypeEnum.MINION && it.isEnemyTauntLike()
        }
        if (hasTaunt) return  // 有嘲讽时不强制打脸
        val rivalHero = rival.playArea.hero ?: return
        for (m in unchecked.sortedByDescending { it.atc }) {
            if (!m.isExhausted && m.atc > 0) {
                log.info { "防呆打脸: ${m.entityName}(${m.atc}/${m.health})→敌方英雄" }
                m.action.attack(rivalHero)
                Thread.sleep((80..150).random().toLong())
            }
        }
    }

    /**
     * 卡牌打出防呆：power()返回null时重试一次，再失败则autoPower兜底
     */
    private fun safePower(c: Card, target: Card? = null) {
        val result = if (target != null) c.action.power(target) else c.action.power()
        if (result == null) {
            log.warn { "卡牌打出失败，重试: ${c.entityName.ifEmpty { c.cardId }}" }
            Thread.sleep((300..500).random().toLong())
            if (target != null) c.action.power(target) else c.action.power()
        }
    }

    override fun reset() {
        super.reset()
    }
}
