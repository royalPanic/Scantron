package com.example.scantron.ui.navigation

import java.net.URLDecoder
import java.net.URLEncoder

sealed class NavRoutes(val route: String) {
    object Containers : NavRoutes("containers")
    object TagLookup : NavRoutes("tag_lookup")
    object Search : NavRoutes("search")
    object ContainerDetail : NavRoutes("container_detail/{containerId}") {
        fun createRoute(containerId: String) = "container_detail/${UriEncoder.encode(containerId)}"
    }
}

object UriEncoder {
    fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
    fun decode(value: String): String {
        return URLDecoder.decode(value, "UTF-8")
    }
}
