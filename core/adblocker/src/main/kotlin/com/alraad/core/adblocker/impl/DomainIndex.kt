package com.alraad.core.adblocker.impl

/**
 * HashSet for exact matches plus a reversed-label trie for suffix (parent-domain)
 * matches, so "ads.example.com" is blocked when either it or "example.com" is listed.
 */
class DomainIndex(domains: Set<String>) {
    private val exact = HashSet(domains)
    private val root = Node()

    private class Node {
        val children = HashMap<String, Node>()
        var terminal = false
    }

    init {
        for (domain in domains) {
            var node = root
            for (label in domain.split('.').asReversed()) {
                node = node.children.getOrPut(label) { Node() }
            }
            node.terminal = true
        }
    }

    fun contains(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized in exact) return true
        val labels = normalized.split('.').asReversed()
        var node = root
        var matched = false
        var walking = true
        var i = 0
        while (walking && i < labels.size) {
            val next = node.children[labels[i]]
            walking = next != null && !next.terminal
            matched = next?.terminal == true
            if (next != null) node = next
            i++
        }
        return matched
    }

    val size: Int get() = exact.size
}
