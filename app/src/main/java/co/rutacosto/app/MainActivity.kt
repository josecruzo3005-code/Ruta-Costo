package co.rutacosto.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var origin: EditText
    private lateinit var destination: EditText
    private lateinit var consumption: EditText
    private lateinit var fuelPrice: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        origin = findViewById(R.id.origin)
        destination = findViewById(R.id.destination)
        consumption = findViewById(R.id.consumption)
        fuelPrice = findViewById(R.id.fuel_price)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        findViewById<Button>(R.id.location_button).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.calculate_button).setOnClickListener { calculateRoutes() }
    }

    private fun requestLocation() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "Activa la ubicación del teléfono.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val currentLocation = manager.getLastKnownLocation(provider)
            if (currentLocation != null) {
                origin.setText(String.format(Locale.US, "%.6f, %.6f", currentLocation.latitude, currentLocation.longitude))
                Toast.makeText(this, "Ubicación obtenida.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Aún no hay una ubicación disponible. Intenta de nuevo en unos segundos.", Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "No se concedió permiso de ubicación.", Toast.LENGTH_LONG).show()
        }
    }

    private fun calculateRoutes() {
        val from = origin.text.toString().trim()
        val to = destination.text.toString().trim()
        val kmPerGallon = number(consumption.text.toString())
        val price = number(fuelPrice.text.toString())
        if (from.isBlank() || to.isBlank()) {
            status.text = "Falta el origen o el destino."
            return
        }
        if (kmPerGallon == null || kmPerGallon <= 0 || price == null || price < 0) {
            status.text = "Revisa consumo y precio del combustible."
            return
        }
        if (BuildConfig.ROUTES_API_KEY.isBlank()) {
            status.text = "Falta configurar la clave de Google Routes API."
            result.text = "La app ya tiene integrada la consulta de rutas reales. La clave se añadirá de forma segura en la compilación."
            return
        }
        status.text = "Calculando rutas reales…"
        result.text = "Consultando distancia, tiempo y peajes."
        executor.execute {
            try {
                val response = requestRoutes(from, to)
                val routes = parseRoutes(response)
                runOnUiThread {
                    if (routes.isEmpty()) {
                        status.text = "No se encontraron rutas."
                        result.text = "Prueba con nombres de ciudades o direcciones más precisas."
                    } else {
                        status.text = "${routes.size} rutas encontradas"
                        result.text = routes.mapIndexed { index, route ->
                            formatRoute(index, route, kmPerGallon, price)
                        }.joinToString("\n\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "No fue posible calcular la ruta."
                    result.text = e.message ?: "Error de conexión."
                }
            }
        }
    }

    private fun requestRoutes(from: String, to: String): String {
        val connection = URL("https://routes.googleapis.com/directions/v2:computeRoutes").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Goog-Api-Key", BuildConfig.ROUTES_API_KEY)
        connection.setRequestProperty(
            "X-Goog-FieldMask",
            "routes.distanceMeters,routes.duration,routes.routeLabels,routes.travelAdvisory.tollInfo,routes.legs.steps.navigationInstruction.instructions"
        )

        val body = JSONObject()
            .put("origin", JSONObject().put("address", from))
            .put("destination", JSONObject().put("address", to))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("computeAlternativeRoutes", true)
            .put("routeModifiers", JSONObject().put("vehicleInfo", JSONObject().put("emissionType", "GASOLINE")))
            .put("extraComputations", JSONArray().put("TOLLS"))
            .put("languageCode", "es-CO")
            .put("units", "METRIC")

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Google Routes API ($code): $text")
        return text
    }

    private data class RouteData(
        val distanceKm: Double,
        val durationSeconds: Long,
        val tollCop: Double,
        val instructions: List<String>
    )

    private fun parseRoutes(json: String): List<RouteData> {
        val routes = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        val result = mutableListOf<RouteData>()
        for (i in 0 until routes.length()) {
            val route = routes.getJSONObject(i)
            val distanceKm = route.optDouble("distanceMeters", 0.0) / 1000.0
            val durationSeconds = parseDurationSeconds(route.optString("duration"))
            val tollInfo = route.optJSONObject("travelAdvisory")?.optJSONObject("tollInfo")
            val tollPrices = tollInfo?.optJSONArray("estimatedPrice")
            var tollCop = 0.0
            if (tollPrices != null) {
                for (j in 0 until tollPrices.length()) {
                    val price = tollPrices.getJSONObject(j)
                    if (price.optString("currencyCode") == "COP") {
                        tollCop += price.optDouble("units", 0.0)
                        tollCop += price.optDouble("nanos", 0.0) / 1_000_000_000.0
                    }
                }
            }
            val instructions = mutableListOf<String>()
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val steps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                    for (s in 0 until minOf(steps.length(), 4)) {
                        val instruction = steps.getJSONObject(s).optJSONObject("navigationInstruction")?.optString("instructions")?.trim()
                        if (!instruction.isNullOrBlank()) instructions.add(instruction)
                    }
                }
            }
            result.add(RouteData(distanceKm, durationSeconds, tollCop, instructions))
        }
        return result
    }

    private fun formatRoute(index: Int, route: RouteData, kmPerGallon: Double, price: Double): String {
        val gallons = route.distanceKm / kmPerGallon
        val fuel = gallons * price
        val total = fuel + route.tollCop
        val minutes = route.durationSeconds / 60
        val hours = minutes / 60
        val mins = minutes % 60
        val time = if (hours > 0) "${hours} h ${mins} min" else "${mins} min"
        val label = when (index) {
            0 -> "MÁS RÁPIDA / PRINCIPAL"
            1 -> "ALTERNATIVA 1"
            else -> "ALTERNATIVA ${index}"
        }
        return buildString {
            append(label)
            append("\nDistancia: ${String.format(Locale.US, "%.1f km", route.distanceKm)}")
            append("\nTiempo estimado: $time")
            append("\nPeajes: ${money(route.tollCop)}")
            append("\nCombustible: ${money(fuel)}")
            append("\nCosto total: ${money(total)}")
            append("\nCosto por km: ${money(if (route.distanceKm > 0) total / route.distanceKm else 0.0)}")
            if (route.instructions.isNotEmpty()) {
                append("\nTrazado inicial:")
                route.instructions.forEachIndexed { instructionIndex, instruction ->
                    append("\n${instructionIndex + 1}. $instruction")
                }
            }
        }
    }

    private fun parseDurationSeconds(value: String): Long =
        value.removeSuffix("s").toDoubleOrNull()?.roundToInt()?.toLong() ?: 0L

    private fun number(value: String): Double? =
        value.trim().replace(".", "").replace(",", ".").toDoubleOrNull()

    private fun money(value: Double): String =
        "$" + value.roundToInt().toString().reversed().chunked(3).joinToString(".").reversed()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
    }
}
