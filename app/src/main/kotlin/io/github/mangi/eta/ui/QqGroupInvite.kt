package io.github.mangi.eta.ui

import android.net.Uri

internal fun qqGroupInviteUri(groupNumber: String): Uri {
    require(groupNumber.isNotBlank() && groupNumber.all(Char::isDigit))
    return Uri.Builder()
        .scheme("mqqapi")
        .authority("card")
        .appendPath("show_pslcard")
        .appendQueryParameter("src_type", "internal")
        .appendQueryParameter("version", "1")
        .appendQueryParameter("uin", groupNumber)
        .appendQueryParameter("card_type", "group")
        .build()
}
