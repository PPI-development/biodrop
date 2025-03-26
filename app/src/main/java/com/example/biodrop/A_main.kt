package com.example.biodrop

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONException
import org.json.JSONArray
import com.google.android.gms.maps.model.BitmapDescriptorFactory


// В бинарном восславлении возвышая голоса,
// Адепты Бога-Машины железною решимостью пробуждают дремлющий дух.


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private val client = OkHttpClient()
    private val serverUrl = "http://10.0.0.1:5000" // Замените на IP вашего Raspberry Pi
    private lateinit var googleMap: GoogleMap
    private val path: MutableList<LatLng> = mutableListOf()
    private val eventMarkers: MutableList<LatLng> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val modeSpinner: Spinner = findViewById(R.id.modeSpinner)
        val intervalInput: EditText = findViewById(R.id.intervalInput)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val statusText: TextView = findViewById(R.id.statusText)

        // Настройка Spinner для выбора режима
        ArrayAdapter.createFromResource(
            this,
            R.array.modes_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinner.adapter = adapter
        }

        startButton.setOnClickListener {
            val mode = modeSpinner.selectedItem.toString().lowercase()
            val interval = intervalInput.text.toString().toIntOrNull()

            if (interval != null && interval > 0) {
                startMission(mode, interval) { response ->
                    runOnUiThread {
                        statusText.text = response
                    }
                }
            } else {
                Toast.makeText(this, "Введите корректный интервал", Toast.LENGTH_SHORT).show()
            }
        }

        stopButton.setOnClickListener {
            stopMission { response ->
                runOnUiThread {
                    statusText.text = response
                }
            }
        }

        // Настройка карты
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Запуск обновления местоположения
        Thread {
            while (true) {
                updateLocationFromServer()
                Thread.sleep(2000)  // Обновление каждые 2 секунды
            }
        }.start()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID
    }

    private fun updateLocationFromServer() {
        val url = "$serverUrl/location"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Ошибка подключения: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    if (body != null && body.startsWith("{")) {
                        val jsonResponse = JSONObject(body)
                        val currentCoords = jsonResponse.getJSONObject("current_coords")
                        val lat = currentCoords.optDouble("lat", Double.NaN)
                        val lon = currentCoords.optDouble("lon", Double.NaN)
                        val events = jsonResponse.optJSONArray("events") ?: JSONArray()

                        // Проверка координат
                        if (!lat.isNaN() && !lon.isNaN()) {
                            val currentLocation = LatLng(lat, lon)
                            runOnUiThread {
                                updateMap(currentLocation, events)
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Координаты недоступны", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        throw JSONException("Invalid JSON response")
                    }
                } catch (e: JSONException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Ошибка обработки данных", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        })
    }



    private fun updateMap(currentLocation: LatLng, events: JSONArray = JSONArray()) {
        // Очищаем карту перед обновлением
        googleMap.clear()

        // Добавляем текущую позицию
        googleMap.addMarker(MarkerOptions().position(currentLocation).title("Текущее местоположение"))
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15f))

        // Отрисовка пройденного пути
        path.add(currentLocation)
        googleMap.addPolyline(PolylineOptions().addAll(path).color(0xFFFF0000.toInt()))

        // Добавляем маркеры для событий
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val eventLat = event.optDouble("lat", Double.NaN)
            val eventLon = event.optDouble("lon", Double.NaN)

            if (!eventLat.isNaN() && !eventLon.isNaN()) {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(eventLat, eventLon))
                        .title("Событие двигателя")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            }
        }
    }


    private fun startMission(mode: String, interval: Int, callback: (String) -> Unit) {
        val json = JSONObject()
        json.put("mode", mode)
        json.put("interval", interval)

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/start")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Ошибка подключения: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.body?.string() ?: "Пустой ответ от сервера")
            }
        })
    }

    private fun stopMission(callback: (String) -> Unit) {
        val body = ByteArray(0).toRequestBody(null)
        val request = Request.Builder()
            .url("$serverUrl/stop")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Ошибка подключения: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.body?.string() ?: "Пустой ответ от сервера")
            }
        })
    }
}
