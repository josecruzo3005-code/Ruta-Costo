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
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var origin: EditText
    private lateinit var km: EditText
    private lateinit var consumption: EditText
    private lateinit var fuelPrice: EditText
    private lateinit var tolls: EditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        origin = findViewById(R.id.origin)
        km = findViewById(R.id.distance)
        consumption = findViewById(R.id.consumption)
        fuelPrice = findViewById(R.id.fuel_price)
        tolls = findViewById(R.id.tolls)
        result = findViewById(R.id.result)
        findViewById<Button>(R.id.location_button).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.calculate_button).setOnClickListener { calculate() }
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
                origin.setText(currentLocation.latitude.toString() + ", " + currentLocation.longitude.toString())
                Toast.makeText(this, "Ubicación obtenida.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Aún no hay una ubicación disponible. Intenta de nuevo en unos segundos.", Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "No se concedió permiso de ubicación.", Toast.LENGTH_LONG).show()
        }
    }

    private fun calculate() {
        val distance = km.text.toString().replace(",", ".").toDoubleOrNull()
        val kmPerGallon = consumption.text.toString().replace(",", ".").toDoubleOrNull()
        val price = fuelPrice.text.toString().replace(".", "").replace(",", ".").toDoubleOrNull()
        val tollCost = tolls.text.toString().replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        if (distance == null || distance <= 0 || kmPerGallon == null || kmPerGallon <= 0 || price == null || price < 0) {
            result.text = "Revisa distancia, consumo y precio del combustible."
            return
        }
        val gallons = distance / kmPerGallon
        val fuelCost = gallons * price
        val total = fuelCost + tollCost
        val perKm = total / distance
        result.text = "Combustible: " + money(fuelCost) + "\nPeajes: " + money(tollCost) + "\nCosto estimado: " + money(total) + "\nCosto por km: " + money(perKm)
    }

    private fun money(value: Double): String =
        "$" + value.roundToInt().toString().reversed().chunked(3).joinToString(".").reversed()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
    }
}
