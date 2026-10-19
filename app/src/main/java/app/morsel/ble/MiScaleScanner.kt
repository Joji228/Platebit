package app.morsel.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Listens for the Mi Body Composition Scale 2 while someone stands on it. It only reads the
 * scale's broadcasts, never connects, so the Zepp Life app keeps working alongside it.
 */
class MiScaleScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val hasBluetooth get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    val permissionGranted
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    val bluetoothOn get() = adapter?.isEnabled == true

    /**
     * Every usable frame the scale broadcasts, for as long as the flow is collected. Scanning stops
     * when collection stops. Throws [SecurityException] if the Bluetooth permission is missing.
     */
    @SuppressLint("MissingPermission")
    fun readings(): Flow<MiScaleReading> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(BODY_COMPOSITION) ?: return
                MiScaleParser.parse(data)?.let { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed ($errorCode)"))
            }
        }
        // Match any 13-byte payload under the Body Composition UUID: an all-zero mask ignores the bytes.
        val filter = ScanFilter.Builder()
            .setServiceData(BODY_COMPOSITION, ByteArray(MiScaleParser.PACKET_SIZE), ByteArray(MiScaleParser.PACKET_SIZE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private companion object {
        val BODY_COMPOSITION: ParcelUuid = ParcelUuid.fromString("0000181B-0000-1000-8000-00805F9B34FB")
    }
}
