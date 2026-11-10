package com.gyg.ventas.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class StoreRepository {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val zone = ZoneId.of("America/Santiago")
    private val products get() = db.collection("products")
    private val sales get() = db.collection("sales")

    fun observeProducts(onValue: (List<Product>) -> Unit, onError: (String) -> Unit): ListenerRegistration =
        products.addSnapshotListener { snap, err ->
            if (err != null) { onError(err.localizedMessage ?: "Error leyendo productos"); return@addSnapshotListener }
            onValue(snap?.documents.orEmpty().map { d -> Product(d.id, d.getString("name") ?: "", d.getString("barcode") ?: "", d.getLong("price") ?: 0, d.getLong("stock") ?: 0, d.getLong("minStock") ?: 0, d.getBoolean("active") != false) })
        }

    fun observeSales(dayKey: String, onValue: (List<Sale>) -> Unit, onError: (String) -> Unit): ListenerRegistration =
        sales.whereEqualTo("dayKey", dayKey).addSnapshotListener { snap, err ->
            if (err != null) { onError(err.localizedMessage ?: "Error leyendo ventas"); return@addSnapshotListener }
            val list = snap?.documents.orEmpty().map { document -> document.toSale(dayKey) }.sortedByDescending { it.createdAtMillis }
            onValue(list)
        }

    suspend fun loadMonthlySales(month: YearMonth): List<Sale> {
        val firstDay = dateKey(month.atDay(1))
        val lastDay = dateKey(month.atEndOfMonth())
        return sales
            .whereGreaterThanOrEqualTo("dayKey", firstDay)
            .whereLessThanOrEqualTo("dayKey", lastDay)
            .get()
            .await()
            .documents
            .map { document -> document.toSale(firstDay) }
            .sortedByDescending { it.createdAtMillis }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toSale(fallbackDay: String): Sale {
        val lines = (get("lines") as? List<*>)?.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            SaleLine(
                map["productId"] as? String ?: "",
                map["name"] as? String ?: "",
                map["barcode"] as? String ?: "",
                (map["unitPrice"] as? Number)?.toLong() ?: 0L,
                (map["quantity"] as? Number)?.toLong() ?: 0L
            )
        }.orEmpty()
        return Sale(id, getString("dayKey") ?: fallbackDay, getLong("total") ?: 0L, lines, getTimestamp("createdAt")?.toDate()?.time ?: 0L)
    }

    suspend fun saveProduct(product: Product) {
        require(product.name.isNotBlank()) { "Escribe el nombre del producto." }
        require(product.price > 0 && product.stock >= 0 && product.minStock >= 0) { "El precio debe ser mayor que cero y el stock no puede ser negativo." }
        val barcode = product.barcode.trim()
        val duplicate = if (barcode.isBlank()) null else products.whereEqualTo("barcode", barcode).get().await().documents.firstOrNull { it.id != product.id }
        require(duplicate == null) { "Ya existe un producto con ese código de barras." }
        val data = mapOf(
            "name" to product.name.trim(),
            "barcode" to barcode,
            "price" to product.price,
            "stock" to product.stock,
            "minStock" to product.minStock,
            "active" to product.active,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (product.id.isBlank()) products.add(data).await()
        else products.document(product.id).set(data).await()
    }

    suspend fun deactivateProduct(product: Product) {
        products.document(product.id).update(mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    suspend fun addStock(productId: String, quantityReceived: Long): Long {
        require(quantityReceived > 0) { "Ingresa una cantidad mayor que cero." }
        val productRef = products.document(productId)
        return db.runTransaction { tx ->
            val snapshot = tx.get(productRef)
            check(snapshot.exists()) { "El producto ya no existe en el inventario." }
            check(snapshot.getBoolean("active") != false) { "El producto está retirado del inventario." }
            val currentStock = snapshot.getLong("stock") ?: 0L
            check(quantityReceived <= Long.MAX_VALUE - currentStock) { "La cantidad supera el máximo permitido." }
            val newStock = currentStock + quantityReceived
            tx.update(productRef, mapOf("stock" to newStock, "updatedAt" to FieldValue.serverTimestamp()))
            newStock
        }.await()
    }

    suspend fun confirmSale(cart: Map<Product, Long>, dayKey: String): String {
        require(cart.isNotEmpty()) { "Agrega productos a la venta." }
        val uid = auth.currentUser?.uid ?: error("La sesión expiró. Inicia sesión nuevamente.")
        val saleRef = sales.document()
        return db.runTransaction { tx ->
            val current = cart.map { (product, qty) ->
                val ref = products.document(product.id)
                val snapshot = tx.get(ref)
                check(snapshot.exists()) { "${product.name} ya no existe en el inventario." }
                val stock = snapshot.getLong("stock") ?: 0
                val name = snapshot.getString("name") ?: product.name
                val barcode = snapshot.getString("barcode") ?: product.barcode
                val price = snapshot.getLong("price") ?: 0
                check(snapshot.getBoolean("active") != false) { "$name ya no está disponible." }
                check(price > 0) { "El precio de $name no es válido." }
                check(qty > 0 && stock >= qty) { "Stock insuficiente para ${product.name}. Disponible: $stock." }
                Triple(ref, Product(snapshot.id, name, barcode, price, stock, snapshot.getLong("minStock") ?: 0, true), qty)
            }
            val lines = current.map { (_, product, qty) -> mapOf("productId" to product.id, "name" to product.name, "barcode" to product.barcode, "unitPrice" to product.price, "quantity" to qty) }
            val total = current.sumOf { (_, p, q) -> p.price * q }
            current.forEach { (ref, product, qty) ->
                tx.update(ref, mapOf("stock" to (product.stock - qty), "updatedAt" to FieldValue.serverTimestamp()))
            }
            tx.set(saleRef, mapOf("dayKey" to dayKey, "total" to total, "lines" to lines, "createdAt" to FieldValue.serverTimestamp(), "userId" to uid))
            saleRef.id
        }.await()
    }

    suspend fun updateSale(saleId: String, requestedLines: List<SaleLine>) {
        val saleRef = sales.document(saleId)
        db.runTransaction { tx ->
            val saleSnapshot = tx.get(saleRef)
            check(saleSnapshot.exists()) { "La venta ya no existe." }
            val oldLines = saleLines(saleSnapshot.get("lines"))
            check(oldLines.isNotEmpty()) { "La venta no contiene artículos que se puedan corregir." }

            val originalByProduct = oldLines.associateBy { it.productId }
            val requestedByProduct = requestedLines.associateBy { it.productId }
            check(requestedByProduct.size == requestedLines.size) { "Hay artículos repetidos en la corrección." }
            check(requestedByProduct.keys.all(originalByProduct::containsKey)) { "No se pueden agregar artículos a una venta existente." }
            check(requestedLines.all { it.quantity > 0 }) { "La cantidad debe ser mayor que cero." }

            val changes = oldLines.map { old ->
                val newQuantity = requestedByProduct[old.productId]?.quantity ?: 0L
                old to newQuantity
            }
            val changedLines = changes.filter { (old, newQuantity) -> old.quantity != newQuantity }
            val productSnapshots = changedLines.associate { (line, _) ->
                val productId = line.productId
                check(productId.isNotBlank()) { "Falta el vínculo de un artículo con el inventario." }
                val ref = products.document(productId)
                productId to tx.get(ref)
            }

            changedLines.forEach { (line, newQuantity) ->
                val ref = products.document(line.productId)
                val productSnapshot = productSnapshots[line.productId]
                check(productSnapshot?.exists() == true) { "${line.name} ya no existe en el inventario; no se pudo corregir la venta." }
                val stock = productSnapshot.getLong("stock") ?: 0L
                val stockChange = line.quantity - newQuantity
                check(stock + stockChange >= 0) { "No hay stock suficiente de ${line.name} para aumentar la cantidad." }
                tx.update(ref, mapOf("stock" to (stock + stockChange), "updatedAt" to FieldValue.serverTimestamp()))
            }

            val finalLines = changes.mapNotNull { (old, newQuantity) ->
                if (newQuantity == 0L) null else old.copy(quantity = newQuantity)
            }
            check(finalLines.isNotEmpty()) { "Para quitar todos los artículos, anula la venta completa." }
            val total = finalLines.sumOf { it.total }
            tx.update(saleRef, mapOf(
                "lines" to finalLines.map { mapOf("productId" to it.productId, "name" to it.name, "barcode" to it.barcode, "unitPrice" to it.unitPrice, "quantity" to it.quantity) },
                "total" to total,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
        }.await()
    }

    suspend fun deleteSale(saleId: String) {
        val saleRef = sales.document(saleId)
        db.runTransaction { tx ->
            val saleSnapshot = tx.get(saleRef)
            check(saleSnapshot.exists()) { "La venta ya no existe." }
            val lines = saleLines(saleSnapshot.get("lines"))
            check(lines.all { it.productId.isNotBlank() && it.quantity > 0 }) { "Falta el vínculo con el inventario de algún artículo; no se anuló la venta." }
            val quantitiesByProduct = lines
                .groupBy { it.productId }
                .mapValues { (_, productLines) -> productLines.sumOf { it.quantity } }
            val stockUpdates = quantitiesByProduct.map { (productId, quantity) ->
                val ref = products.document(productId)
                ref to (tx.get(ref) to quantity)
            }
            stockUpdates.forEach { (ref, stockInfo) ->
                val productSnapshot = stockInfo.first
                val quantity = stockInfo.second
                check(productSnapshot.exists()) { "No se pudo devolver el stock porque falta un producto de esta venta." }
                val stock = productSnapshot.getLong("stock") ?: 0L
                tx.update(ref, mapOf("stock" to (stock + quantity), "updatedAt" to FieldValue.serverTimestamp()))
            }
            tx.delete(saleRef)
        }.await()
    }

    private fun saleLines(rawLines: Any?): List<SaleLine> = (rawLines as? List<*>)?.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        SaleLine(
            map["productId"] as? String ?: "",
            map["name"] as? String ?: "",
            map["barcode"] as? String ?: "",
            (map["unitPrice"] as? Number)?.toLong() ?: 0L,
            (map["quantity"] as? Number)?.toLong() ?: 0L
        )
    }.orEmpty()

    fun todayKey(): String = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
    fun dateKey(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    fun parseDay(key: String): LocalDate = LocalDate.parse(key)
}
