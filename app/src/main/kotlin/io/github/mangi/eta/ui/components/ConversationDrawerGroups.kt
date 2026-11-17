package io.github.mangi.eta.ui.components

import io.github.mangi.eta.fork.conversations.ConversationFolderTree
import io.github.mangi.eta.fork.conversations.MAX_CONVERSATION_FOLDER_DEPTH
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
    val children: List<ConversationDrawerGroup> = emptyList(),
) {
    val conversationCount: Int = items.size + children.sumOf { it.conversationCount }
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Archive : ConversationDrawerSection
    data class Folder(val folder: ConversationFolderUi) : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String, val key: String = label) : ConversationDrawerSection
}

internal val ConversationDrawerSection.stableKey: String
    get() = when (this) {
        ConversationDrawerSection.Pinned -> "pinned"
        ConversationDrawerSection.Archive -> "archive"
        is ConversationDrawerSection.Folder -> "folder:${folder.id}"
        ConversationDrawerSection.Today -> "today"
        is ConversationDrawerSection.Dated -> "dated:$key"
    }

internal fun List<ConversationSummaryUi>.groupForDrawer(
    folders: List<ConversationFolderUi>,
    archived: Boolean,
    searching: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ConversationDrawerGroup> {
    if (archived) {
        return if (isEmpty()) emptyList() else listOf(ConversationDrawerGroup(ConversationDrawerSection.Archive, this))
    }
    val groups = mutableListOf<ConversationDrawerGroup>()
    val pinned = filter(ConversationSummaryUi::isPinned)
        .sortedByDescending(ConversationSummaryUi::updatedAtMillis)
    if (pinned.isNotEmpty()) groups += ConversationDrawerGroup(ConversationDrawerSection.Pinned, pinned)
    val knownFolderIds = folders.mapTo(hashSetOf(), ConversationFolderUi::id)
    val tree = ConversationFolderTree(folders, { it.id }, { it.parentId })
    // Bound rendering depth even when a damaged backup contains an excessively deep tree.
    val parents = tree.rows.associate { row -> row.item.id to
        if (row.depth < MAX_CONVERSATION_FOLDER_DEPTH) tree.parentOf(row.item.id)
        else tree.ancestors(row.item.id)[MAX_CONVERSATION_FOLDER_DEPTH - 2].id
    }
    val children = tree.rows.map { it.item }.groupBy { parents[it.id] }
    val members = filterNot(ConversationSummaryUi::isPinned).groupBy { it.folderId }
    val folderGroups = mutableMapOf<String, ConversationDrawerGroup>()
    for (row in tree.rows.asReversed()) {
        val folder = row.item
        val nested = children[folder.id].orEmpty().mapNotNull { folderGroups[it.id] }
        val items = members[folder.id].orEmpty().sortedWith(
            compareBy<ConversationSummaryUi> { it.folderSortOrder }.thenByDescending { it.updatedAtMillis },
        )
        val group = ConversationDrawerGroup(ConversationDrawerSection.Folder(folder), items, nested)
        if (!searching || group.conversationCount > 0) folderGroups[folder.id] = group
    }
    val folderRoots = children[null].orEmpty().mapNotNull { folderGroups[it.id] }
    val ungrouped = filter { conversation ->
        !conversation.isPinned &&
            (conversation.folderId == null || conversation.folderId !in knownFolderIds)
    }.sortedByDescending(ConversationSummaryUi::updatedAtMillis)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val datedGroups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    ungrouped.forEach { conversation ->
        val section = conversation.drawerSection(now, zoneId)
        datedGroups.getOrPut(section) { mutableListOf() } += conversation
    }
    // 置顶之后先展示文件夹，再进入今天及更早的时间分组。
    groups += folderRoots
    groups += datedGroups.map { (section, items) -> ConversationDrawerGroup(section, items) }
    return groups
}

private fun ConversationSummaryUi.drawerSection(
    today: LocalDate,
    zoneId: ZoneId,
): ConversationDrawerSection {
    if (isActiveRun) return ConversationDrawerSection.Today
    if (updatedAtMillis <= 0L) {
        return ConversationDrawerSection.Dated("时间未知", "unknown")
    }
    val target = Instant.ofEpochMilli(updatedAtMillis).atZone(zoneId).toLocalDate()
    val dayDelta = ChronoUnit.DAYS.between(target, today).toInt()
    if (dayDelta == 0) return ConversationDrawerSection.Today
    if (dayDelta < 0) {
        if (dayDelta == -1) {
            return ConversationDrawerSection.Dated("明天 · ${target.weekdayLabel()}", "tomorrow")
        }
        return ConversationDrawerSection.Dated(
            label = "${target.monthValue}月${target.dayOfMonth}日 · ${target.weekdayLabel()}",
            key = "future:$target",
        )
    }
    if (dayDelta == 1) {
        return ConversationDrawerSection.Dated("昨天 · ${target.weekdayLabel()}", "yesterday")
    }

    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    if (target >= thisMonday) {
        return ConversationDrawerSection.Dated(
            label = target.weekdayLabel(),
            key = "day:$target",
        )
    }

    val targetMonday = target.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastMonday = thisMonday.minusWeeks(1)
    if (targetMonday == lastMonday) {
        return ConversationDrawerSection.Dated("上周", "week:$targetMonday")
    }

    if (YearMonth.from(target) == YearMonth.from(today)) {
        val sunday = targetMonday.plusDays(6)
        return ConversationDrawerSection.Dated(
            label = "${targetMonday.monthValue}月${targetMonday.dayOfMonth}日—" +
                "${sunday.monthValue}月${sunday.dayOfMonth}日",
            key = "week:$targetMonday",
        )
    }

    val month = YearMonth.from(target)
    val label = if (month.year == today.year) {
        "${month.monthValue}月"
    } else {
        "${month.year}年${month.monthValue}月"
    }
    return ConversationDrawerSection.Dated(label, "month:$month")
}

private fun LocalDate.weekdayLabel(): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
