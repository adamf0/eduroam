package id.ac.unpak.wifi.eduroam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

data class WifiProfileResponse(
    val ssid: String,
    val domain: String,
    val identity: String,
    val sam_account_name: String,
    val kategori: String,
    val ca_certificate: String,
    val user_certificate: String,
    val user_private_key: String
)

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : FlutterActivity() {

    private val CHANNEL = "id.ac.unpak.wifi/wifi"
    private var channel: MethodChannel? = null

    private val LOCATION_PERMISSION_REQUEST_CODE = 101

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var specifierNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private var pendingCode: String? = null
    private var pendingToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLinkIntent(intent)
        // Minta semua izin di awal buka aplikasi (seperti kode lama)
        checkAndRequestPermissionsAtStartup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        registerNetworkCallback()
        // Notify Dart to check wifi status when app is resumed
        notifyWifiStatusChanged()
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "checkPendingDeepLink" -> {
                    val map = mutableMapOf<String, String?>()
                    if (pendingCode != null) map["code"] = pendingCode
                    if (pendingToken != null) map["token"] = pendingToken
                    result.success(map)
                    pendingCode = null
                    pendingToken = null
                }
                "requestPermissions" -> {
                    val granted = checkLocationPermission()
                    result.success(granted)
                }
                "openSsoPortal" -> {
                    openSsoPortal()
                    result.success(null)
                }
                "installWifiSuggestion" -> {
                    val profileMap = call.arguments as? Map<*, *>
                    if (profileMap != null) {
                        val profile = WifiProfileResponse(
                            ssid = profileMap["ssid"] as? String ?: "",
                            domain = profileMap["domain"] as? String ?: "",
                            identity = profileMap["identity"] as? String ?: "",
                            sam_account_name = profileMap["sam_account_name"] as? String ?: "",
                            kategori = profileMap["kategori"] as? String ?: "",
                            ca_certificate = profileMap["ca_certificate"] as? String ?: "",
                            user_certificate = profileMap["user_certificate"] as? String ?: "",
                            user_private_key = profileMap["user_private_key"] as? String ?: ""
                        )
                        installWifiSuggestion(profile)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENT", "Profile data was null", null)
                    }
                }
                "disconnectWifi" -> {
                    disconnectEduroam()
                    result.success(true)
                }
                "getWifiStatus" -> {
                    val status = getCurrentWifiStatusMap()
                    result.success(status)
                }
                "triggerInitialWifiSuggestionPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiSuggestionHelper.triggerInitialWifiSuggestionPermission(this)
                    }
                    result.success(true)
                }
                "openWifiSettings" -> {
                    openWifiSettings()
                    result.success(true)
                }
                "isWifiEnabled" -> {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    result.success(wifiManager.isWifiEnabled)
                }
                "setWifiEnabled" -> {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        openWifiSettings()
                        true
                    } else {
                        try {
                            @Suppress("DEPRECATION")
                            wifiManager.isWifiEnabled = true
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    result.success(success)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun handleDeepLinkIntent(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null && data.scheme == "unpakwifi" && data.host == "connect") {
            val token = data.getQueryParameter("token")
            val code = data.getQueryParameter("code")
            if (!token.isNullOrEmpty() || !code.isNullOrEmpty()) {
                pendingCode = code
                pendingToken = token
                // If channel is initialized, send immediately
                channel?.invokeMethod("onDeepLinkReceived", mapOf("code" to code, "token" to token))
            }
        }
    }

    private fun openSsoPortal() {
        val portalUrl = "https://gerbang.unpak.ac.id/realms/gateway/protocol/openid-connect/auth?client_id=akses-internet&redirect_uri=unpakwifi://connect&response_type=code&scope=openid"
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(this, Uri.parse(portalUrl))
    }

    private fun installWifiSuggestion(profile: WifiProfileResponse) {
        try {
            val privateKey = parsePrivateKeyPem(profile.user_private_key)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiSuggestionHelper.installWifiSuggestion(this, profile, privateKey) {
                    val prefs = getSharedPreferences("UnpakWifiPrefs", MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("username", profile.sam_account_name)
                        putString("kategori", profile.kategori)
                        apply()
                    }
                    notifyWifiStatusChanged()
                }
            } else {
                installWifiLegacy(profile, privateKey)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error inisialisasi EAP-TLS: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun installWifiLegacy(wifiData: WifiProfileResponse, privateKey: PrivateKey) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val cf = CertificateFactory.getInstance("X.509")
            val caCert = cf.generateCertificate(ByteArrayInputStream(wifiData.ca_certificate.toByteArray())) as X509Certificate
            val userCert = cf.generateCertificate(ByteArrayInputStream(wifiData.user_certificate.toByteArray())) as X509Certificate

            val enterpriseConfig = WifiEnterpriseConfig().apply {
                eapMethod = WifiEnterpriseConfig.Eap.TLS
                caCertificate = caCert
                setClientKeyEntry(privateKey, userCert)
                identity = wifiData.identity
                domainSuffixMatch = wifiData.domain
            }

            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"${wifiData.ssid}\""
                status = WifiConfiguration.Status.ENABLED
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                this.enterpriseConfig = enterpriseConfig
            }

            val configuredNetworks = wifiManager.configuredNetworks
            if (configuredNetworks != null) {
                for (conf in configuredNetworks) {
                    if (conf.SSID == "\"${wifiData.ssid}\"") {
                        wifiManager.removeNetwork(conf.networkId)
                    }
                }
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                val success = wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()

                if (success) {
                    val prefs = getSharedPreferences("UnpakWifiPrefs", MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("username", wifiData.sam_account_name)
                        putString("kategori", wifiData.kategori)
                        apply()
                    }
                    notifyWifiStatusChanged()
                    Toast.makeText(this, "Jaringan eduroam berhasil dikonfigurasi! Menghubungkan...", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Gagal mengaktifkan konfigurasi eduroam.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Gagal memasang konfigurasi eduroam di sistem.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error inisialisasi EAP-TLS: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectEduroam() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiSuggestionHelper.disconnectEduroam(this, "eduroam")
        } else {
            disconnectEduroamLegacy("eduroam")
        }

        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            specifierNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
            specifierNetworkCallback = null
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("UnpakWifiPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        notifyWifiStatusChanged()

        Toast.makeText(this, "Profil eduroam dihapus. Wi-Fi akan terputus.", Toast.LENGTH_LONG).show()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (_: Exception) {
            // Abaikan
        }
    }

    @Suppress("DEPRECATION")
    private fun disconnectEduroamLegacy(ssid: String) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val configuredNetworks = wifiManager.configuredNetworks
            if (configuredNetworks != null) {
                for (conf in configuredNetworks) {
                    if (conf.SSID == "\"$ssid\"") {
                        wifiManager.removeNetwork(conf.networkId)
                        wifiManager.saveConfiguration()
                    }
                }
            }
        } catch (_: Exception) {
            // Abaikan
        }
    }

    private fun parsePrivateKeyPem(pem: String): PrivateKey {
        val cleanPem = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")

        val decoded = Base64.decode(cleanPem, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    private fun checkLocationPermission(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiSuggestionHelper.triggerInitialWifiSuggestionPermission(this)
                }
                notifyWifiStatusChanged()
            } else {
                Toast.makeText(this, "Aplikasi membutuhkan izin lokasi & perangkat terdekat untuk mengonfigurasi Wi-Fi.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAndRequestPermissionsAtStartup() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiSuggestionHelper.triggerInitialWifiSuggestionPermission(this)
            }
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyWifiStatusChanged()
            }

            override fun onLost(network: Network) {
                notifyWifiStatusChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                notifyWifiStatusChanged()
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {
            // Abaikan
        }
    }

    private fun unregisterNetworkCallback() {
        if (networkCallback != null) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
            } catch (_: Exception) {
                // Abaikan
            }
            networkCallback = null
        }
    }

    internal fun getCurrentWifiStatusMap(): Map<String, Any> {
        val statusMap = mutableMapOf<String, Any>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            statusMap["isConnected"] = false
            return statusMap
        }

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: ""

            if (ssid == "eduroam") {
                statusMap["isConnected"] = true
                
                val prefs = getSharedPreferences("UnpakWifiPrefs", Context.MODE_PRIVATE)
                statusMap["username"] = prefs.getString("username", "Aktif via Sertifikat") ?: "Aktif via Sertifikat"
                statusMap["kategori"] = prefs.getString("kategori", "Dosen / Mahasiswa") ?: "Dosen / Mahasiswa"
                statusMap["ipAddress"] = getIpAddress(wifiInfo)

                val rssi = wifiInfo?.rssi ?: 0
                val level = WifiManager.calculateSignalLevel(rssi, 5)
                val signalText = when (level) {
                    4 -> "Sangat Bagus ($rssi dBm)"
                    3 -> "Bagus ($rssi dBm)"
                    2 -> "Cukup ($rssi dBm)"
                    1 -> "Buruk ($rssi dBm)"
                    else -> "Sangat Buruk ($rssi dBm)"
                }
                statusMap["signalStrength"] = signalText
                statusMap["rssi"] = rssi
                statusMap["internetStatus"] = "Aktif (eduroam)"
            } else {
                statusMap["isConnected"] = false
            }
        } else {
            statusMap["isConnected"] = false
        }
        return statusMap
    }

    fun notifyWifiStatusChanged() {
        handler.post {
            val status = getCurrentWifiStatusMap()
            channel?.invokeMethod("onWifiStatusChanged", status)
        }
    }

    private fun getIpAddress(wifiInfo: WifiInfo?): String {
        val ipAddress = wifiInfo?.ipAddress ?: 0
        if (ipAddress == 0) return "0.0.0.0"
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    private fun openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
