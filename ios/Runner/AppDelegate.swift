import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {
  var pendingCode: String? = nil
  var pendingToken: String? = nil

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    let controller : FlutterViewController = window?.rootViewController as! FlutterViewController
    let channel = FlutterMethodChannel(name: "id.ac.unpak.wifi/wifi",
                                              binaryMessenger: controller.binaryMessenger)
    channel.setMethodCallHandler({
      [weak self] (call: FlutterMethodCall, result: @escaping FlutterResult) -> Void in
      guard let self = self else { return }
      switch call.method {
      case "checkPendingDeepLink":
        let map = ["code": self.pendingCode, "token": self.pendingToken]
        self.pendingCode = nil
        self.pendingToken = nil
        result(map)
      case "requestPermissions":
        result(true)
      case "openSsoPortal":
        if let url = URL(string: "https://gerbang.unpak.ac.id/realms/gateway/protocol/openid-connect/auth?client_id=akses-internet&redirect_uri=unpakwifi://connect&response_type=code&scope=openid") {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        result(nil)
      case "installWifiSuggestion":
        result(true)
      case "disconnectWifi":
        result(true)
      case "getWifiStatus":
        result(["isConnected": false])
      case "isWifiEnabled":
        result(true)
      case "setWifiEnabled":
        result(true)
      case "openWifiSettings":
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        result(true)
      default:
        result(FlutterMethodNotImplemented)
      }
    })

    GeneratedPluginRegistrant.register(with: self)
    
    // Check if app was launched via URL deep link
    if let url = launchOptions?[.url] as? URL {
        handleDeepLink(url: url)
    }
    
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  override func application(
    _ app: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey : Any] = [:]
  ) -> Bool {
    handleDeepLink(url: url)
    return super.application(app, open: url, options: options)
  }

  private func handleDeepLink(url: URL) {
    if url.scheme == "unpakwifi" && url.host == "connect" {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: true)
        let code = components?.queryItems?.first(where: { $0.name == "code" })?.value
        let token = components?.queryItems?.first(where: { $0.name == "token" })?.value
        
        if code != nil || token != nil {
            pendingCode = code
            pendingToken = token
            
            if let controller = window?.rootViewController as? FlutterViewController {
                let channel = FlutterMethodChannel(name: "id.ac.unpak.wifi/wifi", binaryMessenger: controller.binaryMessenger)
                channel.invokeMethod("onDeepLinkReceived", arguments: ["code": code, "token": token])
            }
        }
    }
  }
}
