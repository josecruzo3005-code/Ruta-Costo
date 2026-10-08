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
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
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
        if (BuildConfig.ORS_API_KEY.isBlank()) {
            status.text = "Falta configurar la clave gratuita de OpenRouteService."
            result.text = "La integración ya está preparada. Crea una cuenta en openrouteservice.org y guarda la clave como secreto ORS_API_KEY en GitHub para compilar la app."
            return
        }
        status.text = "Buscando lugares y calculando rutas…"
        result.text = "Consultando OpenRouteService. Esto puede tardar unos segundos."
        executor.execute {
            try {
                val start = geocode(from)
                val end = geocode(to)
                var lastRouteError: Exception? = null
                val candidates = listOf("fastest", "recommended", "shortest").mapNotNull { preference ->
                    try {
                        requestRoute(start, end, preference)
                    } catch (e: Exception) {
                        lastRouteError = e
                        null
                    }
                }
                val routes = deduplicate(candidates)
                if (routes.isEmpty() && lastRouteError != null) throw lastRouteError!!
                runOnUiThread {
                    if (routes.isEmpty()) {
                        status.text = "No se encontraron rutas."
                        result.text = "Comprueba los nombres de origen y destino o intenta con una dirección más precisa."
                    } else {
                        status.text = "${routes.size} opción(es) de ruta calculada(s)"
                        result.text = routes.mapIndexed { index, route ->
                            formatRoute(index, route, kmPerGallon, price)
                        }.joinToString("\n\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "No fue posible calcular la ruta."
                    result.text = e.message ?: "Error de conexión. Revisa internet y la clave de OpenRouteService."
                }
            }
        }
    }

    private data class Coordinates(val longitude: Double, val latitude: Double, val label: String)

    private fun geocode(query: String): Coordinates {
        val coordinateMatch = Regex("^\\s*(-?\\d+(?:[.,]\\d+)?)\\s*[,;]\\s*(-?\\d+(?:[.,]\\d+)?)\\s*$").matchEntire(query)
        if (coordinateMatch != null) {
            val first = coordinateMatch.groupValues[1].replace(",", ".").toDouble()
            val second = coordinateMatch.groupValues[2].replace(",", ".").toDouble()
            // The location button fills latitude, longitude. Also accept common longitude, latitude order.
            val latitude: Double
            val longitude: Double
            if (abs(first) <= 90 && abs(second) <= 180) {
                latitude = first
                longitude = second
            } else {
                longitude = first
                latitude = second
            }
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw IllegalArgumentException("Las coordenadas no son válidas.")
            }
            return Coordinates(longitude, latitude, query)
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = requestText(
            "https://api.openrouteservice.org/geocode/search?text=$encoded&boundary.country=CO&size=1",
            "GET",
            null
        )
        val features = JSONObject(json).optJSONArray("features")
        if (features == null || features.length() == 0) {
            throw IllegalArgumentException("No encontré el lugar: $query. Prueba con ciudad y departamento.")
        }
        val feature = features.getJSONObject(0)
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        return Coordinates(coords.getDouble(0), coords.getDouble(1), feature.optJSONObject("properties")?.optString("label").orEmpty().ifBlank { query })
    }

    private data class RouteData(
        val preference: String,
        val distanceKm: Double,
        val durationSeconds: Double,
        val instructions: List<String>
    )

    private fun requestRoute(start: Coordinates, end: Coordinates, preference: String): RouteData? {
        val coordinates = JSONArray()
            .put(JSONArray().put(start.longitude).put(start.latitude))
            .put(JSONArray().put(end.longitude).put(end.latitude))
        val body = JSONObject()
            .put("coordinates", coordinates)
            .put("preference", preference)
            .put("units", "km")
            .put("language", "es")
            .put("instructions", true)
        val json = requestText(
            "https://api.openrouteservice.org/v2/directions/driving-car/json",
            "POST",
            body.toString()
        )
        val routes = JSONObject(json).optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val route = routes.getJSONObject(0)
        val summary = route.optJSONObject("summary") ?: return null
        val segments = route.optJSONArray("segments")
        val instructions = mutableListOf<String>()
        if (segments != null) {
            for (i in 0 until segments.length()) {
                val steps = segments.getJSONObject(i).optJSONArray("steps") ?: continue
                for (j in 0 until steps.length()) {
                    val instruction = steps.getJSONObject(j).optString("instruction").trim()
                    if (instruction.isNotBlank()) instructions.add(instruction)
                    if (instructions.size >= 8) break
                }
                if (instructions.size >= 8) break
            }
        }
        return RouteData(preference, summary.optDouble("distance", 0.0), summary.optDouble("duration", 0.0), instructions)
    }

    private fun requestText(url: String, method: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("Authorization", BuildConfig.ORS_API_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: text } catch (_: Exception) { text }
                throw IllegalStateException("OpenRouteService ($code): $detail")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun deduplicate(candidates: List<RouteData>): List<RouteData> {
        val result = mutableListOf<RouteData>()
        for (candidate in candidates.sortedBy { it.durationSeconds }) {
            val duplicate = result.any {
                val distanceDifference = abs(it.distanceKm - candidate.distanceKm) / maxOf(it.distanceKm, candidate.distanceKm, 1.0)
                val durationDifference = abs(it.durationSeconds - candidate.durationSeconds) / maxOf(it.durationSeconds, candidate.durationSeconds, 1.0)
                distanceDifference < 0.015 && durationDifference < 0.04
            }
            if (!duplicate) result.add(candidate)
        }
        return result
    }

    private fun formatRoute(index: Int, route: RouteData, kmPerGallon: Double, price: Double): String {
        val gallons = route.distanceKm / kmPerGallon
        val fuel = gallons * price
        val minutes = (route.durationSeconds / 60).roundToInt()
        val hours = minutes / 60
        val mins = minutes % 60
        val time = if (hours > 0) "${hours} h ${mins} min" else "${mins} min"
        val label = when {
            index == 0 -> "MÁS RÁPIDA"
            route.preference == "shortest" -> "MENOR DISTANCIA"
            route.preference == "recommended" -> "RECOMENDADA"
            else -> "ALTERNATIVA"
        }
        return buildString {
            append(label)
            append("\nDistancia: ${String.format(Locale("es", "CO"), "%.1f km", route.distanceKm)}")
            append("\nTiempo estimado: $time")
            append("\nCombustible estimado: ${money(fuel)}")
            append("\nPeajes: no incluidos; deben verificarse por separado")
            append("\nTotal parcial sin peajes: ${money(fuel)}")
            append("\nCosto por km sin peajes: ${money(if (route.distanceKm > 0) fuel / route.distanceKm else 0.0)}")
            if (route.instructions.isNotEmpty()) {
                append("\nIndicaciones principales:")
                route.instructions.forEachIndexed { instructionIndex, instruction ->
                    append("\n${instructionIndex + 1}. $instruction")
                }
            }
        }
    }

    private fun number(value: String): Double? {
        val clean = value.trim().replace(" ", "")
        if (clean.isEmpty()) return null
        val normalized = when {
            clean.contains(",") -> clean.replace(".", "").replace(",", ".")
            Regex("^\\d{1,3}(?:\\.\\d{3})+$").matches(clean) -> clean.replace(".", "")
            else -> clean
        }
        return normalized.toDoubleOrNull()
    }

    private fun money(value: Double): String {
        val rounded = value.roundToInt().toString()
        val grouped = rounded.reversed().chunked(3).joinToString(".").reversed()
        return "\\${grouped} COP"
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
    }
}
