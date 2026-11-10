package com.gyg.ventas.data

data class Product(
    val id: String = "",
    val name: String = "",
    val barcode: String = "",
    val price: Long = 0,
    val stock: Long = 0,
    val minStock: Long = 0,
    val active: Boolean = true
)

data class SaleLine(val productId: String, val name: String, val barcode: String, val unitPrice: Long, val quantity: Long) {
    val total: Long get() = unitPrice * quantity
}

data class Sale(val id: String, val dayKey: String, val total: Long, val lines: List<SaleLine>, val createdAtMillis: Long)
