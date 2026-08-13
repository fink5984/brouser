package com.alphainventor.filemanager

import android.graphics.Bitmap
import java.util.UUID

data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New tab",
    val url: String = "",
    val favicon: Bitmap? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean = other is Tab && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
