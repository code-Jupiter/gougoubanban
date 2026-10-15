package com.littlekitty.xiaokele.provider

fun isSafeHttpUrl(raw: String): Boolean =
    runCatching {
        val uri = java.net.URI(raw)
        (uri.scheme == "http" || uri.scheme == "https") && uri.host?.isNotBlank() == true
    }.getOrDefault(false)
