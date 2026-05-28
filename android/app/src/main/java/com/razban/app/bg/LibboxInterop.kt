package com.razban.app.bg

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator

/**
 * Kotlin-side implementations of the small libbox iterator interfaces. libbox
 * has no helper to build a StringIterator from Java, so when we pass lists
 * INTO the Go core (per-app package lists, certificate lists, interface
 * addresses) we wrap them ourselves.
 */
class StringList(private val items: List<String>) : StringIterator {
    private var i = 0
    override fun len(): Int = items.size
    override fun hasNext(): Boolean = i < items.size
    override fun next(): String = items[i++]
}

class NetIfaceList(private val items: List<NetworkInterface>) : NetworkInterfaceIterator {
    private var i = 0
    override fun hasNext(): Boolean = i < items.size
    override fun next(): NetworkInterface = items[i++]
}
