package com.deniscerri.ytdl.database.models

data class FormatRecyclerView(
    var label: String? = null,
    var format: Format? = null,
    // YTindir: a label that opens and closes its group (dubbed audio formats)
    var group: String? = null,
)
