package com.eevdf.capabilities.multidevicesync.logic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.UUID

/**
 * The radio, and nothing else: list the phones this phone is already paired
 * with, listen for one of them, or connect to one of them.
 *
 * Classic Bluetooth RFCOMM, the "secure" variant: it only works between phones
 * that are paired in Android's Bluetooth settings, and the link is encrypted
 * by Bluetooth itself. There is no discovery (scanning) at all, so the only
 * permission needed on Android 12+ is BLUETOOTH_CONNECT ("Nearby devices").
 *
 * Every call that touches the adapter can throw SecurityException if that
 * permission was withdrawn; callers treat it like the radio being off.
 */
internal class BluetoothLink(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val isAvailable: Boolean get() = adapter != null

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isOn(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    /** Paired devices as (name, address). Empty without permission. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<Pair<String, String>> = try {
        adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
    } catch (_: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun listen(): BluetoothServerSocket {
        val a = adapter ?: throw IOException("This phone has no Bluetooth")
        return try {
            a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (e: SecurityException) {
            throw IOException("Nearby devices permission is missing", e)
        }
    }

    /** Blocks until connected or failed (Android gives up after roughly 5–12 s). */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(address: String): BluetoothSocket {
        val a = adapter ?: throw IOException("This phone has no Bluetooth")
        try {
            val socket = a.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID)
            try {
                socket.connect()
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) { }
                throw e
            }
            return socket
        } catch (e: SecurityException) {
            throw IOException("Nearby devices permission is missing", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a valid Bluetooth address", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun remoteAddress(socket: BluetoothSocket): String? = try { socket.remoteDevice?.address } catch (_: SecurityException) { null }

    companion object {
        private const val SERVICE_NAME = "EEVDF Scheduler sync"
        /** Fixed for this app; both phones must use the same one. */
        val SERVICE_UUID: UUID = UUID.fromString("6f3c2a1e-9b84-4c55-8a0e-5e1b2d7e0e5d")
    }
}
