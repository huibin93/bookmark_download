package com.localarchive.wechat.data.model

enum class LinkType {
    ARTICLE,
    ALBUM,
    PROFILE,
    EXTERNAL,
    UNKNOWN,
}

enum class LinkSource {
    SHARE,
    VIEW_INTENT,
    PASTE,
    IMPORT,
    DISCOVERED,
}

enum class LinkStatus {
    CAPTURED,
    OPENED,
    QUEUED,
    SAVED,
    SKIPPED_DUPLICATE,
    NEEDS_MANUAL_OPEN,
    FAILED,
}

enum class TaskType {
    SAVE_ARTICLE,
    EXPAND_ALBUM,
    IMPORT_BOOKMARKS,
    DOWNLOAD_ASSETS,
}

enum class TaskStatus {
    PENDING,
    RUNNING,
    SAVED,
    SKIPPED_DUPLICATE,
    NEEDS_MANUAL_OPEN,
    FAILED,
    CANCELLED,
}
