package id.ac.unpak.wifi.eduroam

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.PrivateKey

@RequiresApi(Build.VERSION_CODES.Q)
object WifiSuggestionHelper {

    fun triggerInitialWifiSuggestionPermission(activity: Activity) {
        val prefs = activity.getSharedPreferences("UnpakWifiPrefs", Context.MODE_PRIVATE)
        val hasUsername = prefs.contains("username")
        val hasTriggered = prefs.getBoolean("hasTriggeredInitialSetup", false)

        if (!hasUsername && !hasTriggered) {
            val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val dummySuggestion = WifiNetworkSuggestion.Builder()
                .setSsid("eduroam-setup")
                .setWpa2Passphrase("dummyPassword123")
                .setIsAppInteractionRequired(false)
                .build()

            try {
                wifiManager.addNetworkSuggestions(listOf(dummySuggestion))
                prefs.edit().putBoolean("hasTriggeredInitialSetup", true).apply()
            } catch (e: Exception) {
                // Abaikan
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun installWifiSuggestion(
        activity: MainActivity,
        wifiData: WifiProfileResponse,
        privateKey: PrivateKey,
        onSuccess: () -> Unit
    ) {
        try {
            val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 1. Parse CA Certificate
            val cf = CertificateFactory.getInstance("X.509")
            val caCert = cf.generateCertificate(ByteArrayInputStream(wifiData.ca_certificate.toByteArray())) as X509Certificate

            // 2. Parse User Certificate
            val userCert = cf.generateCertificate(ByteArrayInputStream(wifiData.user_certificate.toByteArray())) as X509Certificate

            // 3. Buat WifiEnterpriseConfig EAP-TLS
            val enterpriseConfig = WifiEnterpriseConfig().apply {
                eapMethod = WifiEnterpriseConfig.Eap.TLS
                caCertificate = caCert
                setClientKeyEntry(privateKey, userCert)
                identity = wifiData.identity
                domainSuffixMatch = wifiData.domain
            }

            // 4. Buat Suggestion
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(wifiData.ssid)
                .setWpa2EnterpriseConfig(enterpriseConfig)
                .setIsAppInteractionRequired(false)
                .build()

            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                onSuccess()

                // Minta perpindahan Wi-Fi fisik secara langsung menggunakan WifiNetworkSpecifier (memaksa pindah dari Wi-Fi lama)
                try {
                    val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                    // Bersihkan callback specifier lama jika ada
                    activity.specifierNetworkCallback?.let {
                        try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) {}
                    }

                    val specifier = WifiNetworkSpecifier.Builder()
                        .setSsid(wifiData.ssid)
                        .setWpa2EnterpriseConfig(enterpriseConfig)
                        .build()

                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specifier)
                        .build()

                    activity.specifierNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            try {
                                connectivityManager.bindProcessToNetwork(network)
                            } catch (e: Exception) {}
                            activity.notifyWifiStatusChanged()
                        }
                    }

                    connectivityManager.requestNetwork(request, activity.specifierNetworkCallback!!)
                } catch (e: Exception) {
                    // Abaikan jika device tidak mendukung specifier
                }

                Toast.makeText(activity, "Jaringan eduroam berhasil dikonfigurasi! Menghubungkan...", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "Gagal memasang konfigurasi eduroam di sistem.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Error inisialisasi EAP-TLS: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    fun disconnectEduroam(activity: MainActivity, ssid: String) {
        val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val currentSuggestions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.networkSuggestions
        } else {
            // Android 10 (Q) has removeNetworkSuggestions but doesn't have .networkSuggestions list query easily.
            // Construct suggestion template and remove it.
            val suggestion = WifiNetworkSuggestion.Builder().setSsid(ssid).build()
            listOf(suggestion)
        }
        wifiManager.removeNetworkSuggestions(currentSuggestions)
    }
}
