package com.gyg.ventas

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.gyg.ventas.data.Product
import com.gyg.ventas.data.Sale
import com.gyg.ventas.data.StoreRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val money = NumberFormat.getCurrencyInstance(Locale("es", "CL")).apply { maximumFractionDigits = 0 }
private fun clp(value: Long) = money.format(value)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF285B4B), onPrimary = Color.White,
                secondary = Color(0xFFD99042), onSecondary = Color(0xFF271606),
                tertiary = Color(0xFF6B7F70),
                background = Color(0xFFF5F3ED), onBackground = Color(0xFF20251F),
                surface = Color(0xFFFFFEFA), onSurface = Color(0xFF20251F),
                surfaceVariant = Color(0xFFECE9E0), onSurfaceVariant = Color(0xFF555D55)
            )) { AppRoot() }
        }
    }
}

@Composable private fun AppRoot() {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) { val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }; auth.addAuthStateListener(listener); onDispose { auth.removeAuthStateListener(listener) } }
    if (user == null) LoginScreen(onSignedIn = { user = auth.currentUser }) else StoreScreen(onSignOut = { auth.signOut(); user = null })
}

@Composable private fun LoginScreen(onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope(); val auth = remember { FirebaseAuth.getInstance() }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Ventas GYG", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Inventario y ventas", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it; error = "" }, label = { Text("Correo") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it; error = "" }, label = { Text("Contraseña") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = { busy = true; scope.launch { try { auth.signInWithEmailAndPassword(email.trim(), password).await(); onSignedIn() } catch (e: Exception) { error = e.localizedMessage ?: "No se pudo iniciar sesión." } finally { busy = false } } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "Ingresando…" else "Iniciar sesión") }
        Spacer(Modifier.height(10.dp)); Text("La cuenta se crea desde Firebase Authentication. La app no permite registros públicos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


private enum class Tab(val label: String) { INVENTORY("Inventario"), SALE("Venta"), HISTORY("Historial") }

private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun monthlyCsv(sales: List<Sale>): String = buildString {
    append("\uFEFFFecha,Hora,ID venta,Código,Producto,Cantidad,Precio unitario,Total línea,Total venta\r\n")
    sales.sortedBy { it.createdAtMillis }.forEach { sale ->
        val date = sale.dayKey
        val time = if (sale.createdAtMillis > 0L) java.text.SimpleDateFormat("HH:mm", Locale("es", "CL")).format(java.util.Date(sale.createdAtMillis)) else ""
        sale.lines.forEach { line ->
            append(listOf(date, time, sale.id, line.barcode, line.name, line.quantity.toString(), line.unitPrice.toString(), line.total.toString(), sale.total.toString()).joinToString(";", transform = ::csvCell))
            append("\r\n")
        }
    }
}

@Composable private fun RestockDialog(product: Product, onDismiss: () -> Unit, onAdd: (Long, (String?) -> Unit) -> Unit) {
    var amount by remember(product.id) { mutableStateOf("") }
    var busy by remember(product.id) { mutableStateOf(false) }
    var error by remember(product.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Agregar stock") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(product.name, fontWeight = FontWeight.SemiBold)
            Text("Stock actual: ${product.stock}")
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit); error = "" }, label = { Text("Unidades recibidas") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("La cantidad ingresada se sumará al stock actual.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(enabled = !busy, onClick = {
            val quantity = amount.toLongOrNull()
            if (quantity == null || quantity <= 0) error = "Ingresa una cantidad mayor que cero."
            else { busy = true; onAdd(quantity) { message -> busy = false; error = message.orEmpty() } }
        }) { Text(if (busy) "Guardando…" else "Sumar stock") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable private fun SaleEditDialog(sale: Sale, onDismiss: () -> Unit, onSave: (List<com.gyg.ventas.data.SaleLine>, (String?) -> Unit) -> Unit) {
    var quantities by remember(sale.id) { mutableStateOf(sale.lines.map { it.quantity }) }
    var busy by remember(sale.id) { mutableStateOf(false) }
    var error by remember(sale.id) { mutableStateOf("") }
    val total = sale.lines.indices.sumOf { sale.lines[it].unitPrice * quantities[it] }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Corregir venta") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ajusta las cantidades. Las unidades que quites volverán al inventario.", style = MaterialTheme.typography.bodySmall)
            sale.lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(line.name, fontWeight = FontWeight.SemiBold)
                        Text("Precio de la venta: ${clp(line.unitPrice)}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(enabled = !busy && quantities[index] > 0, onClick = { quantities = quantities.toMutableList().also { it[index]-- } }) { Text("−") }
                            Text("${quantities[index]} unidades")
                            TextButton(enabled = !busy, onClick = { quantities = quantities.toMutableList().also { it[index]++ } }) { Text("+") }
                            Spacer(Modifier.weight(1f))
                            Text(clp(line.unitPrice * quantities[index]))
                        }
                        if (quantities[index] == 0L) Text("Este artículo se quitará de la venta.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Nuevo total", fontWeight = FontWeight.Bold); Text(clp(total), fontWeight = FontWeight.Bold) }
            if (quantities.all { it == 0L }) Text("Para retirar todos los artículos, anula la venta completa.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(enabled = !busy && quantities.any { it > 0 }, onClick = {
            busy = true
            val lines = sale.lines.mapIndexedNotNull { index, line -> quantities[index].takeIf { it > 0 }?.let { line.copy(quantity = it) } }
            onSave(lines) { message -> busy = false; error = message.orEmpty() }
        }) { Text(if (busy) "Guardando…" else "Guardar corrección") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StoreScreen(onSignOut: () -> Unit) {
    val repo = remember { StoreRepository() }; val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.SALE) }; var products by remember { mutableStateOf(emptyList<Product>()) }; var error by remember { mutableStateOf("") }; var info by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(repo.todayKey()) }; var daySales by remember { mutableStateOf(emptyList<Sale>()) }
    var showMonthlyReport by remember { mutableStateOf(false) }
    var reportMonth by remember { mutableStateOf(YearMonth.now(ZoneId.of("America/Santiago"))) }
    var monthSales by remember { mutableStateOf(emptyList<Sale>()) }
    var reportLoading by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val cart = remember { mutableStateMapOf<String, Pair<Product, Long>>() }
    var editor by remember { mutableStateOf<Product?>(null) }; var productToDelete by remember { mutableStateOf<Product?>(null) }
    var productToRestock by remember { mutableStateOf<Product?>(null) }
    var saleToEdit by remember { mutableStateOf<Sale?>(null) }
    var saleToDelete by remember { mutableStateOf<Sale?>(null) }
    var saleActionBusy by remember { mutableStateOf(false) }
    var saleBusy by remember { mutableStateOf(false) }
    DisposableEffect(repo) {
        val reg = repo.observeProducts({ freshProducts ->
            products = freshProducts
            cart.toMap().forEach { (id, pair) ->
                val quantity = pair.second
                val latest = freshProducts.firstOrNull { it.id == id && it.active }
                if (latest == null) cart.remove(id) else cart[id] = latest to quantity
            }
        }, { error = it })
        onDispose { reg.remove() }
    }
    DisposableEffect(selectedDate) { val reg = repo.observeSales(selectedDate, { daySales = it }, { error = it }); onDispose { reg.remove() } }
    val context = LocalContext.current
    LaunchedEffect(showMonthlyReport, reportMonth) {
        if (showMonthlyReport) {
            reportLoading = true
            try { monthSales = repo.loadMonthlySales(reportMonth); error = "" }
            catch (e: Exception) { monthSales = emptyList(); error = e.localizedMessage ?: "No se pudo cargar el informe mensual." }
            finally { reportLoading = false }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                val csv = monthlyCsv(monthSales)
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                    output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                info = "Informe mensual guardado correctamente."
                error = ""
            } catch (e: Exception) { error = e.localizedMessage ?: "No se pudo guardar el informe." }
        }
    }
    var showScanner by remember { mutableStateOf(false) }
    var scannerCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    fun scan(onCode: (String) -> Unit) { scannerCallback = onCode; showScanner = true }
    BackHandler(enabled = showMonthlyReport) { showMonthlyReport = false; error = "" }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (showMonthlyReport) "Informe mensual" else "Ventas GYG", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (showMonthlyReport) TextButton(onClick = { showMonthlyReport = false; error = "" }) { Text("‹ Volver") } },
                actions = {
                    if (showMonthlyReport) {
                        TextButton(enabled = !reportLoading && monthSales.isNotEmpty(), onClick = { exportLauncher.launch("informe-ventas-${reportMonth}.csv") }) { Text("Descargar CSV") }
                    } else {
                        Box {
                            TextButton(onClick = { menuExpanded = true }) { Text("⋮") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Informe mensual") }, onClick = {
                                    menuExpanded = false
                                    reportMonth = YearMonth.now(ZoneId.of("America/Santiago"))
                                    showMonthlyReport = true
                                    info = ""
                                    error = ""
                                })
                                DropdownMenuItem(text = { Text("Salir") }, onClick = { menuExpanded = false; onSignOut() })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = { if (!showMonthlyReport) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { Tab.entries.forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t; error = ""; info = "" }, icon = { Text(when (t) { Tab.INVENTORY -> "▦"; Tab.SALE -> "$"; Tab.HISTORY -> "◷" }) }, label = { Text(t.label) }) } } },
        floatingActionButton = { if (!showMonthlyReport && tab == Tab.INVENTORY) FloatingActionButton(onClick = { editor = Product() }) { Text("+") } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (info.isNotBlank() && !showMonthlyReport) Text(info, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (showMonthlyReport) {
                MonthlyReportScreen(
                    month = reportMonth,
                    sales = monthSales,
                    loading = reportLoading,
                    onPrevious = { reportMonth = reportMonth.minusMonths(1) },
                    onNext = { if (reportMonth < YearMonth.now(ZoneId.of("America/Santiago"))) reportMonth = reportMonth.plusMonths(1) },
                    onExport = { exportLauncher.launch("informe-ventas-${reportMonth}.csv") }
                )
            } else when (tab) {
                Tab.INVENTORY -> InventoryScreen(products, query, { query = it }, { editor = it }, { productToDelete = it }, { scan { code -> val found = products.firstOrNull { it.barcode == code }; if (found != null) productToRestock = found else editor = Product(barcode = code) } })
                Tab.SALE -> SalesScreen(products.filter { it.active }, cart, { product, delta ->
                    val old = cart[product.id]?.second ?: 0
                    val next = old + delta
                    if (next <= 0) cart.remove(product.id)
                    else if (next > product.stock) error = "Stock insuficiente para ${product.name}. Disponible: ${product.stock}."
                    else { cart[product.id] = product to next; error = "" }
                }, { query = it }, query, { scan { code ->
                    val p = products.firstOrNull { it.active && it.barcode == code }
                    if (p == null) error = "No hay un producto activo con código $code."
                    else if (p.stock < 1) error = "${p.name} no tiene stock disponible."
                    else {
                        val qty = (cart[p.id]?.second ?: 0) + 1
                        if (qty > p.stock) error = "Stock insuficiente para ${p.name}. Disponible: ${p.stock}."
                        else { cart[p.id] = p to qty; error = "" }
                    }
                } }, saleBusy, {
                    if (!saleBusy) scope.launch {
                        saleBusy = true
                        try {
                            repo.confirmSale(cart.values.associate { it.first to it.second }, repo.todayKey())
                            cart.clear(); selectedDate = repo.todayKey(); info = "Venta guardada correctamente."; error = ""
                        } catch (e: Exception) { error = e.localizedMessage ?: "No se pudo guardar la venta." }
                        finally { saleBusy = false }
                    }
                })
                Tab.HISTORY -> HistoryScreen(selectedDate, daySales, { selectedDate = it }, { pickDate(context, selectedDate, { selectedDate = it }, repo) }, { saleToEdit = it }, { saleToDelete = it })
            }
        }
    }
    if (showScanner) BarcodeScannerDialog(onBarcode = { code -> showScanner = false; scannerCallback?.invoke(code); scannerCallback = null }, onDismiss = { showScanner = false; scannerCallback = null })
    if (productToRestock != null) RestockDialog(product = productToRestock!!, onDismiss = { productToRestock = null }, onAdd = { quantity, report -> scope.launch { try { val totalStock = repo.addStock(productToRestock!!.id, quantity); info = "Stock actualizado: $totalStock unidades."; error = ""; productToRestock = null; report(null) } catch (e: Exception) { report(e.localizedMessage ?: "No se pudo agregar stock.") } } })
    if (editor != null) ProductEditorDialog(product = editor!!, onDismiss = { editor = null }, onScan = { scan { code -> editor = editor?.copy(barcode = code) } }, onSave = { p, report -> scope.launch { try { repo.saveProduct(p); info = "Producto guardado."; error = ""; editor = null; report(null) } catch (e: Exception) { report(e.localizedMessage ?: "No se pudo guardar el producto.") } } })
    if (productToDelete != null) AlertDialog(
        onDismissRequest = { productToDelete = null },
        title = { Text("Retirar producto") },
        text = { Text("¿Quieres retirar ${productToDelete!!.name} del inventario? Se conservará en el historial de ventas.") },
        confirmButton = { TextButton(onClick = { val p = productToDelete!!; productToDelete = null; scope.launch { try { repo.deactivateProduct(p); info = "Producto retirado del inventario."; error = "" } catch (e: Exception) { error = e.localizedMessage ?: "No se pudo retirar el producto." } } }) { Text("Retirar") } },
        dismissButton = { TextButton(onClick = { productToDelete = null }) { Text("Cancelar") } }
    )
    if (saleToEdit != null) SaleEditDialog(sale = saleToEdit!!, onDismiss = { if (!saleActionBusy) saleToEdit = null }, onSave = { lines, report ->
        if (!saleActionBusy) scope.launch {
            saleActionBusy = true
            try { repo.updateSale(saleToEdit!!.id, lines); info = "Venta corregida; stock y total actualizados."; error = ""; saleToEdit = null; report(null) }
            catch (e: Exception) { report(e.localizedMessage ?: "No se pudo corregir la venta.") }
            finally { saleActionBusy = false }
        }
    })
    if (saleToDelete != null) AlertDialog(
        onDismissRequest = { if (!saleActionBusy) saleToDelete = null },
        title = { Text("Anular venta") },
        text = { Text("Se eliminará esta venta del historial y se devolverán sus artículos al inventario. ¿Continuar?") },
        confirmButton = { TextButton(enabled = !saleActionBusy, onClick = { val sale = saleToDelete!!; saleActionBusy = true; scope.launch { try { repo.deleteSale(sale.id); info = "Venta anulada y stock devuelto al inventario."; error = ""; saleToDelete = null } catch (e: Exception) { error = e.localizedMessage ?: "No se pudo anular la venta."; saleToDelete = null } finally { saleActionBusy = false } } }) { Text(if (saleActionBusy) "Procesando…" else "Anular venta") } },
        dismissButton = { TextButton(enabled = !saleActionBusy, onClick = { saleToDelete = null }) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun InventoryScreen(products: List<Product>, query: String, onQuery: (String) -> Unit, onEdit: (Product) -> Unit, onDelete: (Product) -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar por nombre o código") })
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Escanear para buscar / agregar") }; Text("${products.count { it.active }} productos", modifier = Modifier.align(Alignment.CenterVertically)) }
        val filtered = products.filter { it.active && (query.isBlank() || it.name.contains(query, true) || it.barcode.contains(query, true)) }
        if (filtered.isEmpty()) EmptyState("No hay productos", "Agrega el primero con + o escanea su código.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) { items(filtered, key = { it.id }) { p -> Card(Modifier.fillMaxWidth().clickable { onEdit(p) }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.barcode.ifBlank { "Sin código" }}  ·  Stock ${p.stock}${if (p.stock <= p.minStock) " (bajo)" else ""}", style = MaterialTheme.typography.bodySmall); Text(clp(p.price), color = MaterialTheme.colorScheme.primary) }; TextButton(onClick = { onEdit(p) }) { Text("Editar") }; TextButton(onClick = { onDelete(p) }) { Text("Quitar", color = MaterialTheme.colorScheme.error) } } } } }
    }
}

@Composable private fun ProductEditorDialog(product: Product, onDismiss: () -> Unit, onScan: () -> Unit, onSave: (Product, (String?) -> Unit) -> Unit) {
    var name by remember(product.id) { mutableStateOf(product.name) }; var barcode by remember(product.id, product.barcode) { mutableStateOf(product.barcode) }; var price by remember(product.id) { mutableStateOf(if (product.price == 0L) "" else product.price.toString()) }; var stock by remember(product.id) { mutableStateOf(product.stock.toString()) }; var minStock by remember(product.id) { mutableStateOf(product.minStock.toString()) }
    var saveError by remember(product.id) { mutableStateOf("") }; var saving by remember(product.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (product.id.isBlank()) "Nuevo producto" else "Editar producto") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(barcode, { barcode = it }, label = { Text("Código de barras") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = onScan) { Text("Escanear") } }
        OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Precio (CLP)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(stock, { stock = it.filter(Char::isDigit) }, label = { Text("Stock") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); OutlinedTextField(minStock, { minStock = it.filter(Char::isDigit) }, label = { Text("Stock mínimo") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
        if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { Button(enabled = !saving, onClick = { saveError = ""; saving = true; onSave(product.copy(name = name, barcode = barcode, price = price.toLongOrNull() ?: 0, stock = stock.toLongOrNull() ?: 0, minStock = minStock.toLongOrNull() ?: 0)) { message -> saving = false; saveError = message.orEmpty() } }) { Text(if (saving) "Guardando…" else "Guardar") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun SalesScreen(products: List<Product>, cart: Map<String, Pair<Product, Long>>, onQty: (Product, Long) -> Unit, onSearch: (String) -> Unit, query: String, onScan: () -> Unit, busy: Boolean, onConfirm: () -> Unit) {
    val filtered = products.filter { query.isBlank() || it.name.contains(query, true) || it.barcode.contains(query, true) }
    val total = cart.values.sumOf { it.first.price * it.second }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onSearch, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar producto") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Productos disponibles", fontWeight = FontWeight.Bold); OutlinedButton(onClick = onScan) { Text("Escanear") } }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(filtered, key = { it.id }) { p -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Medium); Text("${clp(p.price)} · stock ${p.stock}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(enabled = p.stock > (cart[p.id]?.second ?: 0), onClick = { onQty(p, 1) }) { Text("Agregar +") } } } } }
        HorizontalDivider(); Text("Carrito (${cart.values.sumOf { it.second }})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        cart.values.forEach { (p, qty) -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(p.name, Modifier.weight(1f), maxLines = 1); TextButton(onClick = { onQty(p, -1) }) { Text("−") }; Text("$qty"); TextButton(onClick = { onQty(p, 1) }) { Text("+") }; Text(clp(p.price * qty)) } }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("TOTAL", fontWeight = FontWeight.Bold); Text(clp(total), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
        Button(onClick = onConfirm, enabled = cart.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "Guardando venta…" else "Confirmar venta") }
    }
}

@Composable private fun MonthlyReportScreen(
    month: YearMonth,
    sales: List<Sale>,
    loading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExport: () -> Unit
) {
    val total = sales.sumOf { it.total }
    val monthLabel = month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es", "CL")))
        .replaceFirstChar { it.uppercase(Locale("es", "CL")) }
    val daily = sales.groupBy { it.dayKey }.entries.sortedByDescending { it.key }
    val bestSellers = sales.flatMap { it.lines }
        .groupBy { it.name }
        .mapValues { (_, lines) -> lines.sumOf { it.quantity } }
        .entries.sortedByDescending { it.value }.take(5)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious) { Text("‹ Anterior") }
            Text(monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onNext) { Text("Siguiente ›") }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (sales.isEmpty()) {
            EmptyState("Mes sin ventas", "No hay ventas registradas en $monthLabel.")
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1.2f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Total vendido", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                        Text(clp(total), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Card(Modifier.weight(0.8f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Ventas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sales.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text("Resumen por día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(daily, key = { it.key }) { (day, dayEntries) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatDay(day), fontWeight = FontWeight.Medium)
                                Text("${dayEntries.size} ${if (dayEntries.size == 1) "venta" else "ventas"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(clp(dayEntries.sumOf { it.total }), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item {
                    Text("Productos más vendidos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            bestSellers.forEachIndexed { index, (name, quantity) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${index + 1}. $name", Modifier.weight(1f), maxLines = 2)
                                    Text("$quantity un.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                                if (index < bestSellers.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Text("Descargar informe CSV") }
        }
    }
}

@Composable private fun HistoryScreen(day: String, sales: List<Sale>, onChangeDay: (String) -> Unit, onPick: () -> Unit, onEditSale: (Sale) -> Unit, onDeleteSale: (Sale) -> Unit) {
    val dayTotal = sales.sumOf { it.total }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Column { Text("Resumen del día", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(formatDay(day)) }; OutlinedButton(onClick = onPick) { Text("Elegir fecha") } }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Total vendido", clp(dayTotal), Modifier.weight(1f)); SummaryCard("Ventas", sales.size.toString(), Modifier.weight(1f))
        }
        Text("Ventas del día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (sales.isEmpty()) EmptyState("Sin ventas", "No hay ventas registradas para esta fecha.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp)) {
            items(sales, key = { it.id }) { sale ->
                var expanded by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (sale.createdAtMillis > 0) java.text.SimpleDateFormat("HH:mm", Locale("es", "CL")).format(java.util.Date(sale.createdAtMillis)) else "Guardando…")
                            Text(clp(sale.total), fontWeight = FontWeight.Bold)
                        }
                        Text("${sale.lines.sumOf { it.quantity }} unidades · ${sale.lines.size} productos", style = MaterialTheme.typography.bodySmall)
                        if (expanded) {
                            sale.lines.forEach { Text("${it.quantity} × ${it.name} — ${clp(it.total)}", modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onEditSale(sale) }) { Text("Editar venta") }
                                TextButton(onClick = { onDeleteSale(sale) }) { Text("Anular venta", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.bodySmall); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) } } }
@Composable private fun EmptyState(title: String, detail: String) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

private fun formatDay(day: String): String = try { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Locale("es", "CL")).withLocale(Locale("es", "CL"))).replaceFirstChar { it.uppercase(Locale("es", "CL")) } } catch (_: Exception) { day }
private fun pickDate(context: android.content.Context, key: String, onSelected: (String) -> Unit, repo: StoreRepository) { val d = repo.parseDay(key); DatePickerDialog(context, { _, y, m, day -> onSelected(repo.dateKey(LocalDate.of(y, m + 1, day))) }, d.year, d.monthValue - 1, d.dayOfMonth).show() }
