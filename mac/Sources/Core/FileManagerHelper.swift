import Foundation
import AppKit
import UserNotifications

public final class FileManagerHelper: @unchecked Sendable {
    public static let shared = FileManagerHelper()
    
    private let prefsKey = "BridgeDownloadsDirectory"
    
    private init() {
        requestNotificationPermission()
    }
    
    public func getDownloadsDirectory() -> URL {
        if let savedPath = UserDefaults.standard.string(forKey: prefsKey), !savedPath.isEmpty {
            let url = URL(fileURLWithPath: savedPath)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                return url
            }
        }
        
        return FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Downloads")
    }
    
    public func setDownloadsDirectory(_ url: URL) {
        UserDefaults.standard.set(url.path, forKey: prefsKey)
        LogManager.shared.log("Updated downloads directory to: \(url.path)")
    }
    
    public func saveReceivedFile(name: String, data: Data) throws -> URL {
        let folder = getDownloadsDirectory()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        
        let targetUrl = resolveUniqueUrl(in: folder, proposedName: name)
        try data.write(to: targetUrl, options: .atomic)
        
        LogManager.shared.log("Saved file to: \(targetUrl.path) (\(data.count) bytes)")
        
        // Notify user with sound and macOS notification
        DispatchQueue.main.async {
            NSSound(named: "Glass")?.play()
            self.postUserNotification(fileName: targetUrl.lastPathComponent, path: targetUrl.path)
        }
        
        return targetUrl
    }
    
    private func resolveUniqueUrl(in directory: URL, proposedName: String) -> URL {
        let baseName = (proposedName as NSString).deletingPathExtension
        let ext = (proposedName as NSString).pathExtension
        
        var destination = directory.appendingPathComponent(proposedName)
        var counter = 1
        
        while FileManager.default.fileExists(atPath: destination.path) {
            let newName: String
            if ext.isEmpty {
                newName = "\(baseName) (\(counter))"
            } else {
                newName = "\(baseName) (\(counter)).\(ext)"
            }
            destination = directory.appendingPathComponent(newName)
            counter += 1
        }
        return destination
    }
    
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
    
    private func postUserNotification(fileName: String, path: String) {
        let content = UNMutableNotificationContent()
        content.title = "File Received"
        content.body = "\(fileName) was saved to your Downloads."
        content.sound = UNNotificationSound.default
        content.userInfo = ["path": path]
        
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
