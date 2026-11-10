# Ventas GYG (Android)

Aplicación Android nativa en Kotlin y Jetpack Compose para inventario, ventas e historial diario conectada al proyecto Firebase `ventas-gyg`.

## Compilar la APK

En PowerShell, desde esta carpeta (`android-app`):

```powershell
.\gradlew.bat assembleDebug
```

APK de depuración: `app\build\outputs\apk\debug\app-debug.apk`.

Para instalar con un teléfono conectado por USB y depuración USB activada:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Preparación de Firebase antes de usar datos reales

1. En Firebase Console, en `ventas-gyg`, habilita **Authentication > Sign-in method > Email/Password**.
2. Crea manualmente la cuenta propietaria desde **Authentication > Users > Add user**. La app no ofrece registro público.
3. Revisa el archivo `..\firestore.rules` y publícalo en Firebase Console o con Firebase CLI. No se ha desplegado desde este proyecto.
4. En Firestore, crea `authorizedUsers/{UID_DE_LA_CUENTA}` (documento vacío); usa el UID que aparece en Authentication. Las reglas permiten leer solo el propio documento y bloquean su creación desde la app.
5. Instala e inicia sesión con ese correo y contraseña.

La regla temporal anterior permitía que cualquier persona leyera y modificara la base de datos. Reemplazamos el archivo local por reglas que exigen usuario autenticado y autorizado; hasta que se publiquen, Firebase seguirá aplicando las reglas que ya están en la nube. No uses información real hasta completar esos pasos.

## Funciones

- Inicio de sesión Firebase Authentication.
- Inventario: alta, edición, búsqueda, código de barras, precio CLP, stock y stock mínimo. Escanear un producto existente abre la reposición y suma las unidades recibidas al stock actual.
- Lector de códigos integrado con CameraX y ML Kit; incluye el modelo de lectura en la APK. Filtra formatos de productos, espera una lectura estable en el centro y pide confirmar el código antes de usarlo.
- Ventas: carrito y confirmación atómica con Firestore; se revisa el stock actual y se descuenta junto con la creación de la venta.
- Historial: total y cantidad de ventas por día, selección de fecha y detalle de artículos. Las ventas se pueden corregir por línea o anular; las diferencias se reflejan en el inventario y en el total diario.
- Zona horaria de negocio `America/Santiago`. No imprime tickets.
- Los productos se retiran del inventario marcándolos inactivos para conservar las líneas históricas; las ventas son de solo lectura después de crearse.

## Datos almacenados

`products/{id}` almacena nombre, barcode, precio en pesos enteros, stock, stock mínimo, indicador activo y fecha de actualización. `sales/{id}` conserva fecha comercial, total, detalle de líneas (incluye precio y nombre del momento de venta), fecha de creación y UID.
