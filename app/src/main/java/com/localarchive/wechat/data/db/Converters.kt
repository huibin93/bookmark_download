package com.localarchive.wechat.data.db

import androidx.room.TypeConverter
import com.localarchive.wechat.data.model.LinkSource
import com.localarchive.wechat.data.model.LinkStatus
import com.localarchive.wechat.data.model.LinkType
import com.localarchive.wechat.data.model.TaskStatus
import com.localarchive.wechat.data.model.TaskType

class Converters {
    @TypeConverter fun toLinkType(value: String?): LinkType? = value?.let(LinkType::valueOf)
    @TypeConverter fun fromLinkType(value: LinkType?): String? = value?.name

    @TypeConverter fun toLinkSource(value: String?): LinkSource? = value?.let(LinkSource::valueOf)
    @TypeConverter fun fromLinkSource(value: LinkSource?): String? = value?.name

    @TypeConverter fun toLinkStatus(value: String?): LinkStatus? = value?.let(LinkStatus::valueOf)
    @TypeConverter fun fromLinkStatus(value: LinkStatus?): String? = value?.name

    @TypeConverter fun toTaskType(value: String?): TaskType? = value?.let(TaskType::valueOf)
    @TypeConverter fun fromTaskType(value: TaskType?): String? = value?.name

    @TypeConverter fun toTaskStatus(value: String?): TaskStatus? = value?.let(TaskStatus::valueOf)
    @TypeConverter fun fromTaskStatus(value: TaskStatus?): String? = value?.name
}
