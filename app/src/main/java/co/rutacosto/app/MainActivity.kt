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
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var toll1: EditText
    private lateinit var toll2: EditText
    private lateinit var toll3: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("rutacosto_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        origin = findViewById(R.id.origin)
        destination = findViewById(R.id.destination)
        consumption = findViewById(R.id.consumption)
        fuelPrice = findViewById(R.id.fuel_price)
        toll1 = findViewById(R.id.toll1)
        toll2 = findViewById(R.id.toll2)
        toll3 = findViewById(R.id.toll3)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)

        // Valores base recordados entre usos; ambos campos siguen siendo editables.
        consumption.setText(preferences.getString("consumption_km_gallon", "35").orEmpty().ifBlank { "35" })
        fuelPrice.setText(preferences.getString("fuel_price_cop", "16000").orEmpty().ifBlank { "16000" })
        rememberValue(consumption, "consumption_km_gallon")
        rememberValue(fuelPrice, "fuel_price_cop")
        toll1.setText(preferences.getString("toll_route_1_cop", "0").orEmpty().ifBlank { "0" })
        toll2.setText(preferences.getString("toll_route_2_cop", "0").orEmpty().ifBlank { "0" })
        toll3.setText(preferences.getString("toll_route_3_cop", "0").orEmpty().ifBlank { "0" })
        rememberValue(toll1, "toll_route_1_cop")
        rememberValue(toll2, "toll_route_2_cop")
        rememberValue(toll3, "toll_route_3_cop")

        findViewById<Button>(R.id.location_button).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.calculate_button).setOnClickListener { calculateRoutes() }
    }

    private fun rememberValue(field: EditText, key: String) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                preferences.edit().putString(key, s?.toString().orEmpty()).apply()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
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
        val tolls = listOf(toll1, toll2, toll3).map { number(it.text.toString()) }
        if (from.isBlank() || to.isBlank()) {
            status.text = "Falta el origen o el destino."
            return
        }
        if (kmPerGallon == null || kmPerGallon <= 0 || price == null || price < 0) {
            status.text = "Revisa consumo y precio del combustible."
            return
        }
        if (tolls.any { it == null || it < 0 }) {
            status.text = "Revisa los peajes: usa valores de cero o mayores."
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
                val tollStations = try { loadTollStations() } catch (_: Exception) { emptyList() }
                val candidates = listOf("fastest", "recommended", "shortest").mapNotNull { preference ->
                    try {
                        requestRoute(start, end, preference, tollStations)
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
                            formatRoute(index, route, kmPerGallon, price, tolls[index] ?: 0.0)
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
            "https://api.heigit.org/pelias/v1/search?text=$encoded&boundary.country=CO&size=1",
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

    private data class TollStation(val name: String, val latitude: Double, val longitude: Double, val fareCop: Double, val sector: String)

    private data class RouteData(
        val preference: String,
        val distanceKm: Double,
        val durationSeconds: Double,
        val instructions: List<String>,
        val tollStations: List<TollStation> = emptyList(),
        val tollDataAvailable: Boolean = false
    )

    private fun loadTollStations(): List<TollStation> {
        val url = "https://hermes.invias.gov.co/arcgis/rest/services/OpenData/ServiciosOpenData1/FeatureServer/3/query?where=1%3D1&outFields=nombre,latsig,longsig,cat_1,sector&returnGeometry=false&f=json"
        val json = JSONObject(requestText(url, "GET", null))
        val features = json.optJSONArray("features") ?: throw IllegalStateException("La fuente oficial de peajes no devolvió datos.")
        val stations = mutableListOf<TollStation>()
        for (i in 0 until features.length()) {
            val attrs = features.getJSONObject(i).optJSONObject("attributes") ?: continue
            val name = attrs.optString("nombre").trim()
            val lat = attrs.optDouble("latsig", Double.NaN)
            val lon = attrs.optDouble("longsig", Double.NaN)
            val fare = attrs.optDouble("cat_1", 0.0)
            if (name.isNotBlank() && lat.isFinite() && lon.isFinite() && fare >= 0) stations.add(TollStation(name, lat, lon, fare, attrs.optString("sector")))
        }
        if (stations.isEmpty()) throw IllegalStateException("La fuente oficial de peajes no tiene registros válidos.")
        return stations
    }

    private fun requestRoute(start: Coordinates, end: Coordinates, preference: String, tollStations: List<TollStation>): RouteData? {
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
            "https://api.heigit.org/openrouteservice/v2/directions/driving-car/json",
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
        val routePoints = decodePolyline(route.optString("geometry"))
        val detectedTolls = if (routePoints.isNotEmpty() && tollStations.isNotEmpty()) matchTolls(routePoints, tollStations) else emptyList()
        return RouteData(preference, summary.optDouble("distance", 0.0), summary.optDouble("duration", 0.0), instructions, detectedTolls, tollStations.isNotEmpty())
    }

    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            points.add((lat / 1e5) to (lon / 1e5))
        }
        return points
    }

    private fun matchTolls(routePoints: List<Pair<Double, Double>>, stations: List<TollStation>): List<TollStation> {
        val matched = mutableListOf<TollStation>()
        for (station in stations) {
            var nearestMeters = Double.MAX_VALUE
            for (i in 0 until routePoints.size - 1) {
                val a = routePoints[i]
                val b = routePoints[i + 1]
                nearestMeters = minOf(nearestMeters, pointToSegmentMeters(station.latitude, station.longitude, a.first, a.second, b.first, b.second))
                if (nearestMeters < 100.0) break
            }
            if (nearestMeters <= 250.0 && matched.none { it.name.equals(station.name, ignoreCase = true) }) matched.add(station)
        }
        return matched
    }

    private fun pointToSegmentMeters(lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLat = Math.toRadians((lat + lat1 + lat2) / 3.0)
        val scaleX = 111320.0 * kotlin.math.cos(meanLat)
        val scaleY = 110540.0
        val px = (lon - lon1) * scaleX
        val py = (lat - lat1) * scaleY
        val bx = (lon2 - lon1) * scaleX
        val by = (lat2 - lat1) * scaleY
        val lengthSquared = bx * bx + by * by
        val t = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
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

    private fun formatRoute(index: Int, route: RouteData, kmPerGallon: Double, price: Double, toll: Double): String {
        val gallons = route.distanceKm / kmPerGallon
        val fuel = gallons * price
        val officialTolls = route.tollStations.sumOf { it.fareCop }
        val totalTolls = officialTolls + toll
        val total = fuel + totalTolls
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
            if (route.tollDataAvailable) {
                append("\nPeajes detectados (tarifa oficial cat. I): ${money(officialTolls)}")
                if (route.tollStations.isNotEmpty()) {
                    append("\nPeajes identificados:")
                    route.tollStations.forEach { station -> append("\n• ${station.name}: ${money(station.fareCop)}") }
                } else append("\nNo se identificaron peajes oficiales cercanos a la línea de ruta.")
            } else append("\nPeajes automáticos: no disponibles; no se sumaron tarifas inventadas.")
            if (toll > 0) append("\nAjuste manual adicional: ${money(toll)}")
            append("\nCOSTO TOTAL ESTIMADO: ${money(total)}")
            append("\nCosto por km: ${money(if (route.distanceKm > 0) total / route.distanceKm else 0.0)}")
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
