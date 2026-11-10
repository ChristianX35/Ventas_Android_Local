# Ventas GYG

Aplicación Android para apoyar la operación diaria de una tienda local. Permite registrar productos, mantener el stock al día y guardar las ventas realizadas durante cada jornada.

> **En una frase:** una herramienta móvil para que una tienda lleve inventario y ventas desde Android, usando un único registro compartido en Firebase.

## Comienza aquí

| Si quieres… | Ve a… |
| --- | --- |
| Entender las funciones de la aplicación | [Funciones principales](#funciones-principales) |
| Compilar el código | [Ejecutar el proyecto](#ejecutar-el-proyecto) |
| Instalarla para el personal de una tienda | [Instalarla en los teléfonos](#instalarla-en-los-teléfonos-de-la-tienda) |
| Configurar la seguridad de los datos | [`firestore.rules`](firestore.rules) |

## Qué resuelve

Una tienda pequeña puede llevar sus ventas e inventario desde un teléfono Android, sin depender de planillas manuales. La aplicación mantiene el historial de las ventas y actualiza el stock cuando una venta se confirma, se corrige o se anula.

## Funciones principales

- Inicio de sesión para usuarios autorizados.
- Catálogo de productos con precio en pesos chilenos, stock y stock mínimo.
- Búsqueda y lectura de códigos de barras con la cámara.
- Reposición de productos mediante el escáner.
- Carrito de ventas que descuenta el inventario de forma atómica.
- Historial diario y mensual de ventas.
- Corrección y anulación de ventas, con ajuste automático del stock.

## Tecnologías

- Kotlin y Jetpack Compose.
- Firebase Authentication y Cloud Firestore.
- CameraX y ML Kit para lectura de códigos de barras.
- Zona horaria de negocio: `America/Santiago`.

## Estructura

```text
android-app/       Aplicación Android nativa
firestore.rules    Reglas de acceso para Cloud Firestore
firebase.json      Configuración de Firebase Hosting
public/            Página estática de Firebase Hosting
```

## Ejecutar el proyecto

1. Abre `android-app` con Android Studio.
2. Crea una configuración de Firebase propia y coloca su archivo `google-services.json` en `android-app/app/`.
3. Habilita Firebase Authentication con correo y contraseña.
4. Publica las reglas de `firestore.rules` y agrega usuarios autorizados en la colección `authorizedUsers`.
5. Compila e instala la aplicación desde Android Studio o ejecuta:

   ```powershell
   cd android-app
   .\gradlew.bat assembleDebug
   ```

El archivo `google-services.json`, los APK, los ZIP, los resultados de compilación y los volcados de memoria se excluyen del repositorio.

## Instalarla en los teléfonos de la tienda

1. Configura una sola vez el proyecto Firebase que usará la tienda: habilita el acceso con correo y contraseña, crea las cuentas del personal y registra cada UID en `authorizedUsers`.
2. Publica las reglas de `firestore.rules` en ese proyecto Firebase. Sin este paso, la aplicación no debe usarse con datos reales.
3. Genera una APK de **release firmada** desde Android Studio: **Build > Generate Signed Bundle / APK > APK**. Conserva la clave de firma en un lugar privado; no la subas a GitHub ni la compartas.
4. Transfiere el archivo APK resultante a cada teléfono de la tienda e instálalo. Android puede pedir autorizar la instalación desde la aplicación usada para recibir el archivo.
5. En cada teléfono, abre Ventas GYG e inicia sesión con la cuenta creada para esa persona. Todos los teléfonos conectados al mismo Firebase verán el mismo inventario y las mismas ventas.

Antes de distribuir la APK, realiza una venta de prueba, revisa que cambie el stock y luego anúlala para comprobar que el inventario se repone correctamente.

## Estado

Proyecto orientado a uso local. Antes de registrar información real, configura Firebase y aplica las reglas de Firestore incluidas en este repositorio.
