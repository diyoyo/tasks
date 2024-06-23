package com.todoroo.astrid.api

import kotlinx.parcelize.Parcelize
import org.tasks.filters.Filter
import org.tasks.filters.FilterListItem
import org.tasks.themes.TasksIcons

@Parcelize
data class CustomFilter(
    val filter: org.tasks.data.entity.Filter,
) : Filter {
    override val title: String?
        get() = filter.title
    override val sql: String
        get() = filter.sql!!

    override val valuesForNewTasks: String?
        get() = filter.values

    val criterion: String?
        get() = filter.criterion

    override val order: Int
        get() = filter.order

    val id: Long
        get() = filter.id

    override val icon: String
        get() = filter.icon ?: TasksIcons.FILTER_LIST

    override val tint: Int
        get() = filter.color ?: 0

    override fun areItemsTheSame(other: FilterListItem): Boolean {
        return other is CustomFilter && id == other.id
    }
}
