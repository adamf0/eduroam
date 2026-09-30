import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Connect eduroam',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF11131C),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF573C9D), // Primary Purple
          secondary: Color(0xFFFBBF24), // Accent Gold/Yellow
          surface: Color(0xFF1E2030), // Card Background
          error: Color(0xFFEF4444), // Error Red
        ),
        useMaterial3: true,
      ),
      home: const EduroamPage(),
    );
  }
}

class EduroamPage extends StatefulWidget {
  const EduroamPage({super.key});

  @override
  State<EduroamPage> createState() => _EduroamPageState();
}

class _EduroamPageState extends State<EduroamPage> {
  static const platform = MethodChannel('id.ac.unpak.wifi/wifi');

  bool _isConnected = false;
  String _username = "Aktif via Sertifikat";
  String _kategori = "Dosen / Mahasiswa";
  String _ipAddress = "0.0.0.0";
  String _signalStrength = "Mencari...";
  String _internetStatus = "Connected";

  bool _isLoading = false;
  bool _isConnecting = false;
  String _statusMessage = "Belum Terhubung";

  Timer? _timeoutTimer;

  @override
  void initState() {
    super.initState();
    // Set method call handler for callbacks from Kotlin
    platform.setMethodCallHandler(_handleMethodCall);
    // Check initial wifi status
    _checkWifiStatus();
    // Check if there is any pending deep link (cold start)
    _checkPendingDeepLink();
    // Auto-enable Wi-Fi if turned off
    _autoToggleWifi();
  }

  @override
  void dispose() {
    _cancelTimeout();
    super.dispose();
  }

  Future<void> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onDeepLinkReceived':
        final args = Map<String, dynamic>.from(call.arguments);
        final code = args['code'] as String?;
        final token = args['token'] as String?;
        _fetchWifiProfile(code: code, token: token);
        break;
      case 'onWifiStatusChanged':
        if (call.arguments != null) {
          final args = Map<String, dynamic>.from(call.arguments);
          _updateWifiStatus(args);
        }
        break;
      default:
        break;
    }
  }

  Future<void> _checkWifiStatus() async {
    try {
      final status = await platform.invokeMethod('getWifiStatus');
      if (status != null) {
        _updateWifiStatus(Map<String, dynamic>.from(status));
      }
    } on PlatformException catch (e) {
      debugPrint("Native gagal dipanggil: ${e.message}");
      // Simulasi data dummy jika native belum siap:
      _updateWifiStatus({
        'isConnected': false,
        'statusMessage': 'Mode Simulasi (Native Off)'
      });
    }
  }

  Future<void> _checkPendingDeepLink() async {
    try {
      final pending = await platform.invokeMethod('checkPendingDeepLink');
      if (pending != null) {
        final args = Map<String, dynamic>.from(pending);
        final code = args['code'] as String?;
        final token = args['token'] as String?;
        if (code != null || token != null) {
          _fetchWifiProfile(code: code, token: token);
        }
      }
    } on PlatformException catch (e) {
      debugPrint("Error checking pending deep link: ${e.message}");
    }
  }

  Future<void> _autoToggleWifi() async {
    try {
      final isWifiOn = await platform.invokeMethod<bool>('isWifiEnabled') ?? false;
      if (!isWifiOn) {
        await platform.invokeMethod('setWifiEnabled');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text("Mengaktifkan Wi-Fi secara otomatis..."),
              duration: Duration(seconds: 3),
            ),
          );
        }
      }
    } on PlatformException catch (e) {
      debugPrint("Error auto toggling wifi: ${e.message}");
    }
  }

  void _updateWifiStatus(Map<String, dynamic> status) {
    setState(() {
      _isConnected = status['isConnected'] as bool? ?? false;
      if (_isConnected) {
        _username = status['username'] as String? ?? "Aktif via Sertifikat";
        _kategori = status['kategori'] as String? ?? "Dosen / Mahasiswa";
        _ipAddress = status['ipAddress'] as String? ?? "0.0.0.0";
        _signalStrength = status['signalStrength'] as String? ?? "Mencari...";
        _internetStatus = status['internetStatus'] as String? ?? "Connected";
        _statusMessage = "Terhubung";
        _isConnecting = false;
        _isLoading = false;
        _cancelTimeout();
        // Clear any active warning SnackBars since we connected successfully
        try {
          ScaffoldMessenger.of(context).clearSnackBars();
        } catch (_) {}
      } else {
        if (!_isConnecting) {
          _statusMessage = "Belum Terhubung";
        }
      }
    });
  }

  Future<void> _fetchWifiProfile({String? code, String? token}) async {
    if (code == null && token == null) return;

    setState(() {
      _isLoading = true;
      _statusMessage = "Mengunduh sertifikat...";
    });

    try {
      final body = <String, String>{};
      if (code != null) {
        body['code'] = code;
      } else if (token != null) {
        body['token'] = token;
      }

      final response = await http.post(
        Uri.parse("https://akses.unpak.ac.id/wifi/api_get_profile.php"),
        body: body,
      );

      if (response.statusCode == 200 && !response.body.contains("error")) {
        final profileData = jsonDecode(response.body) as Map<String, dynamic>;

        setState(() {
          _isLoading = false;
          _isConnecting = true;
          _statusMessage = "Menghubungkan...";
        });

        // Pass the profile map to native Android for EAP-TLS config and suggestion
        await platform.invokeMethod('installWifiSuggestion', profileData);
        _startTimeout();
      } else {
        _showError("Autentikasi Gagal",
            "Gagal memproses otentikasi atau sesi kedaluwarsa.");
      }
    } catch (e) {
      _showError("Koneksi API Gagal", "Gagal menghubungi server BPSI: $e");
    }
  }

  void _showError(String title, String message) {
    setState(() {
      _isLoading = false;
      _isConnecting = false;
      _statusMessage = title;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Theme.of(context).colorScheme.error,
      ),
    );
  }

  Future<void> _connectEduroam() async {
    try {
      final hasPermission =
          await platform.invokeMethod<bool>('requestPermissions') ?? false;
      if (hasPermission) {
        final isWifiOn =
            await platform.invokeMethod<bool>('isWifiEnabled') ?? false;
        if (!isWifiOn) {
          await platform.invokeMethod('setWifiEnabled');
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                  "Mengaktifkan Wi-Fi... Silakan ketuk kembali Hubungkan setelah Wi-Fi aktif."),
              duration: Duration(seconds: 4),
            ),
          );
          return;
        }
        await platform.invokeMethod('openSsoPortal');
      }
    } on PlatformException catch (e) {
      debugPrint("Error starting connection flow: ${e.message}");
    }
  }

  Future<void> _disconnectEduroam() async {
    try {
      setState(() {
        _isLoading = true;
      });
      await platform.invokeMethod('disconnectWifi');
      setState(() {
        _isLoading = false;
        _isConnected = false;
        _statusMessage = "Belum Terhubung";
      });
    } on PlatformException catch (e) {
      debugPrint("Error disconnecting wifi: ${e.message}");
      setState(() {
        _isLoading = false;
      });
    }
  }

  void _startTimeout() {
    _cancelTimeout();
    _timeoutTimer = Timer(const Duration(seconds: 20), () {
      if (_isConnecting && !_isConnected) {
        setState(() {
          _isConnecting = false;
          _statusMessage = "Belum Terhubung";
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              "Koneksi otomatis tertunda. Silakan matikan Wi-Fi lain atau pilih eduroam di Setelan Wi-Fi HP.",
              style: TextStyle(color: Colors.white),
            ),
            duration: Duration(seconds: 5),
            backgroundColor: Color(0xFFEF4444),
          ),
        );
        // Prompt connection page settings natively
        platform.invokeMethod('openWifiSettings');
      }
    });
  }

  void _cancelTimeout() {
    _timeoutTimer?.cancel();
    _timeoutTimer = null;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 32),
              // Header Logo & Title
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      color: theme.colorScheme.secondary.withOpacity(0.1),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.wifi_lock_rounded,
                      size: 40,
                      color: theme.colorScheme.secondary,
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    "Connect eduroam",
                    style: TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                      letterSpacing: -0.5,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    "UNIVERSITAS PAKUAN",
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.5,
                      color: Colors.grey[500],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 32),

              // Dynamic Status Cards
              Expanded(
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  child: AnimatedCrossFade(
                    duration: const Duration(milliseconds: 300),
                    crossFadeState: _isConnected
                        ? CrossFadeState.showSecond
                        : CrossFadeState.showFirst,
                    firstChild: Container(
                      width: double.infinity,
                      decoration: BoxDecoration(
                        color: theme.colorScheme.surface,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: Colors.white.withOpacity(0.05),
                          width: 1,
                        ),
                      ),
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            _statusMessage,
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              color: theme.colorScheme.secondary,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            "Hubungkan perangkat Anda secara otomatis ke Wi-Fi Enterprise eduroam menggunakan sertifikat digital terenkripsi.",
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.grey[400],
                              height: 1.4,
                            ),
                          ),
                        ],
                      ),
                    ),
                    secondChild: Container(
                      width: double.infinity,
                      decoration: BoxDecoration(
                        color: theme.colorScheme.surface,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: Colors.white.withOpacity(0.05),
                          width: 1,
                        ),
                      ),
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _buildInfoRow("Username Akses", _username),
                          const Divider(color: Colors.white10, height: 16),
                          _buildInfoRow("Kategori", _kategori),
                          const Divider(color: Colors.white10, height: 16),
                          _buildInfoRow("Alamat IP", _ipAddress),
                          const Divider(color: Colors.white10, height: 16),
                          _buildInfoRow("Kekuatan Sinyal", _signalStrength),
                          const Divider(color: Colors.white10, height: 16),
                          _buildInfoRow(
                            "Internet",
                            _internetStatus,
                            valueColor: const Color(0xFF10B981),
                            valueStyle: const TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              // Bottom Actions
              Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_isLoading || _isConnecting) ...[
                    LinearProgressIndicator(
                      color: theme.colorScheme.secondary,
                      backgroundColor: Colors.white.withOpacity(0.05),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    const SizedBox(height: 16),
                  ],
                  ElevatedButton(
                    onPressed: (_isLoading || _isConnecting)
                        ? null
                        : (_isConnected ? _disconnectEduroam : _connectEduroam),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: _isConnected
                          ? theme.colorScheme.error
                          : theme.colorScheme.primary,
                      foregroundColor: Colors.white,
                      disabledBackgroundColor:
                          theme.colorScheme.primary.withOpacity(0.5),
                      disabledForegroundColor: Colors.white60,
                      minimumSize: const Size.fromHeight(54),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                      elevation: 0,
                    ),
                    child: Text(
                      _isConnecting
                          ? "Menghubungkan..."
                          : (_isConnected
                              ? "Putuskan Sambungan eduroam"
                              : "Hubungkan WiFi eduroam UNPAK"),
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value,
      {Color? valueColor, TextStyle? valueStyle}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: Colors.grey[400],
              ),
            ),
          ),
          Text(
            value,
            style: valueStyle ??
                TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: valueColor ?? Colors.white,
                ),
          ),
        ],
      ),
    );
  }
}
