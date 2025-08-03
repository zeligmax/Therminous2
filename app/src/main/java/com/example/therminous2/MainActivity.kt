package com.example.therminous2

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlin.concurrent.thread
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Sensores y ubicación
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var gyroValues = FloatArray(3)
    private var currentLocation: Location? = null

    // Elementos UI
    private lateinit var inputAltitude: EditText
    private lateinit var inputLatitude: EditText
    private lateinit var inputLongitude: EditText
    private lateinit var btnStart: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var waveTypeSpinner: Spinner
    private lateinit var textValues: TextView

    private lateinit var switchAx: Switch
    private lateinit var switchAy: Switch
    private lateinit var switchAz: Switch
    private lateinit var switchAlt: Switch
    private lateinit var switchLat: Switch
    private lateinit var switchLon: Switch

    // Control de sonido y estado
    private var playing = false
    private var soundThread: Thread? = null
    private var continuousThread: Thread? = null
    private var sensitivityFactor = 1.0
    private var waveType = "Sinusoidal"
    private var lastSensorUpdate = 0L
    private var latestData: List<Double> = listOf(0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Referenciar elementos de la UI
        inputAltitude = findViewById(R.id.inputAltitude)
        inputLatitude = findViewById(R.id.inputLatitude)
        inputLongitude = findViewById(R.id.inputLongitude)
        btnStart = findViewById(R.id.btnStart)
        sensitivitySeekBar = findViewById(R.id.seekBarSensitivity)
        waveTypeSpinner = findViewById(R.id.spinnerWaveType)
        textValues = findViewById(R.id.textValues)

        switchAx = findViewById(R.id.switchAx)
        switchAy = findViewById(R.id.switchAy)
        switchAz = findViewById(R.id.switchAz)
        switchAlt = findViewById(R.id.switchAlt)
        switchLat = findViewById(R.id.switchLat)
        switchLon = findViewById(R.id.switchLon)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupWaveTypeSpinner()
        setupSensitivitySeekBar()

        // Botón de encendido/apagado
        btnStart.setOnClickListener {
            if (!playing) {
                startSensors()
                requestLocation()
                startSoundLoop()
                btnStart.text = "⏹️ Detener sonido"
            } else {
                stopSoundLoop()
                btnStart.text = "🎧 Iniciar sonido"
            }
        }
    }

    // Configurar lista de tipos de onda
    private fun setupWaveTypeSpinner() {
        val options = arrayOf(
            "Sinusoidal",
            "Cuadrada",
            "Triangular",
            "Theremin (buffer)",
            "Theremin casi-continuo",
            "Theremin continuo 🎛️"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        waveTypeSpinner.adapter = adapter
        waveTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                waveType = options[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // Configurar barra de sensibilidad
    private fun setupSensitivitySeekBar() {
        sensitivitySeekBar.max = 100
        sensitivitySeekBar.progress = 50
        sensitivityFactor = 1.0
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityFactor = 0.1 + (progress / 50.0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // Iniciar sensores
    private fun startSensors() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    // Obtener última ubicación conocida
    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) currentLocation = location
        }
    }

    // Iniciar el bucle de sonido
    private fun startSoundLoop() {
        playing = true

        if (waveType == "Theremin continuo") {
            // Bucle con audio continuo
            continuousThread = thread {
                val sampleRate = 44100
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                    AudioTrack.MODE_STREAM
                )
                audioTrack.play()

                while (playing) {
                    val buffer = ShortArray(minBufferSize)
                    val frecuencias = latestData.ifEmpty { listOf(440.0) }
                    for (i in buffer.indices) {
                        val t = i.toDouble() / sampleRate
                        var sample = 0.0
                        for (f in frecuencias) {
                            sample += sin(2 * PI * f * t) * 0.15
                        }
                        sample /= frecuencias.size
                        buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    audioTrack.write(buffer, 0, buffer.size)
                }
                audioTrack.stop()
                audioTrack.release()
            }
        } else {
            // Bucle activado por datos
            soundThread = thread {
                while (playing) {
                    try {
                        val ax = gyroValues[0].toDouble() * sensitivityFactor
                        val ay = gyroValues[1].toDouble() * sensitivityFactor
                        val az = gyroValues[2].toDouble() * sensitivityFactor

                        val alt = inputAltitude.text.toString().toDoubleOrNull() ?: currentLocation?.altitude ?: 0.0
                        val lat = inputLatitude.text.toString().toDoubleOrNull() ?: currentLocation?.latitude ?: 0.0
                        val lon = inputLongitude.text.toString().toDoubleOrNull() ?: currentLocation?.longitude ?: 0.0

                        runOnUiThread {
                            textValues.text = "Ax: %.2f | Ay: %.2f | Az: %.2f\nAlt: %.2f | Lat: %.2f | Lon: %.2f"
                                .format(ax, ay, az, alt, lat, lon)
                        }

                        val valores = mutableListOf<Double>()
                        if (switchAx.isChecked) valores.add(ax)
                        if (switchAy.isChecked) valores.add(ay)
                        if (switchAz.isChecked) valores.add(az)
                        if (switchAlt.isChecked) valores.add(alt)
                        if (switchLat.isChecked) valores.add(lat)
                        if (switchLon.isChecked) valores.add(lon)

                        if (valores.isNotEmpty()) {
                            latestData = normalizarFrecuencias(valores)
                            reproducirSonido(valores)
                        }

                        Thread.sleep(200)
                    } catch (e: Exception) {
                        Log.e("Therminous2", "Error en loop de sonido: ${e.message}")
                    }
                }
            }
        }
    }

    // Detener sonido y liberar recursos
    private fun stopSoundLoop() {
        playing = false
        sensorManager.unregisterListener(this)
        soundThread?.join()
        continuousThread?.interrupt()
        continuousThread = null
    }

    // Normalizar valores a frecuencias audibles
    private fun normalizarFrecuencias(datos: List<Double>): List<Double> {
        return datos.map {
            val capped = it.coerceIn(-30.0, 30.0)
            (capped + 30) / 60.0 * 1000.0 + 300.0
        }
    }

    // Reproducir sonido corto según el tipo de onda
    private fun reproducirSonido(datos: List<Double>) {
        if (waveType == "Theremin continuo 🎛️") return

        val sampleRate = 44100
        val durationSeconds = 0.2
        val numSamples = (sampleRate * durationSeconds).toInt()
        val buffer = ShortArray(numSamples)
        val frecuencias = normalizarFrecuencias(datos)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0
            for (f in frecuencias) {
                sample += when (waveType) {
                    "Cuadrada" -> sign(sin(2 * PI * f * t))
                    "Triangular" -> 2.0 * abs(2.0 * (f * t - floor(f * t + 0.5))) - 1.0
                    else -> sin(2 * PI * f * t)
                } * 0.15
            }
            sample /= frecuencias.size
            buffer[i] = (sample * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer.size * 2,
            AudioTrack.MODE_STATIC
        )

        audioTrack.write(buffer, 0, buffer.size)
        audioTrack.play()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSensorUpdate > 100) {
                gyroValues = event.values.clone()
                lastSensorUpdate = currentTime
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        stopSoundLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSoundLoop()
    }
}

