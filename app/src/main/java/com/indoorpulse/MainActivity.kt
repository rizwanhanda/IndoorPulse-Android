package com.indoorpulse

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.indoorpulse.data.sensor.SensorDataSourceImpl
import com.indoorpulse.domain.usecase.ApplyGpsLeashUseCase
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.presentation.ui.MapScreen
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.ui.theme.IndoorPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IndoorPulseTheme {
                var permissionsGranted by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                    permissionsGranted = fineLocation
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACTIVITY_RECOGNITION
                        )
                    )
                }

                if (permissionsGranted) {
                    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(this@MainActivity) }
                    
                    val factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            val sensorDataSource = SensorDataSourceImpl(this@MainActivity, fusedLocationClient)
                            val database = com.indoorpulse.data.local.IndoorPulseDatabase.getDatabase(this@MainActivity)
                            val sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
                            return MapViewModel(
                                sensorManager,
                                sensorDataSource,
                                FindShortestPathUseCase(),
                                ApplyGpsLeashUseCase(),
                                database.mapDao()
                            ) as T
                        }
                    }

                    val viewModel: MapViewModel = viewModel(factory = factory)
                    MapScreen(viewModel = viewModel)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Location permission is required to use this app.")
                    }
                }
            }
        }
    }
}