package ru.skokova.aiadventchallenge.notifications

import org.slf4j.LoggerFactory
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

/**
 * Сервис для отправки desktop уведомлений
 * Поддерживает Linux (notify-send), macOS (osascript), Windows (SystemTray)
 */
class NotificationService {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    
    /**
     * Отправить уведомление
     *
     * @param title Заголовок уведомления
     * @param message Текст сообщения
     */
    fun notify(title: String, message: String) {
        try {
            when (detectOS()) {
                OS.LINUX -> sendLinuxNotification(title, message)
                OS.MACOS -> sendMacNotification(title, message)
                OS.WINDOWS -> sendWindowsNotification(title, message)
            }
            
            logger.info("📢 [NOTIFICATION] $title: $message")
        } catch (e: Exception) {
            logger.error("Failed to send notification", e)
            logger.error("Notification content - Title: $title, Message: $message")
        }
    }
    
    /**
     * Отправка уведомления в Linux (через notify-send)
     */
    private fun sendLinuxNotification(title: String, message: String) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("notify-send", title, message, "-u", "normal", "-t", "10000")
            )
            process.waitFor()
            logger.debug("Linux notification sent via notify-send")
        } catch (e: Exception) {
            logger.warn("notify-send not available, notification not sent", e)
        }
    }
    
    /**
     * Отправка уведомления в macOS (через osascript)
     */
    private fun sendMacNotification(title: String, message: String) {
        try {
            // Экранируем кавычки для AppleScript
            val escapedMessage = message.replace("\"", "\\\"")
            val escapedTitle = title.replace("\"", "\\\"")
            
            val script = "display notification \"$escapedMessage\" with title \"$escapedTitle\""
            val process = Runtime.getRuntime().exec(
                arrayOf("osascript", "-e", script)
            )
            process.waitFor()
            logger.debug("macOS notification sent via osascript")
        } catch (e: Exception) {
            logger.warn("osascript not available, notification not sent", e)
        }
    }
    
    /**
     * Отправка уведомления в Windows (через SystemTray)
     */
    private fun sendWindowsNotification(title: String, message: String) {
        try {
            if (!SystemTray.isSupported()) {
                logger.warn("SystemTray not supported on this platform")
                return
            }
            
            val tray = SystemTray.getSystemTray()
            
            // Создаём простой иконку (1x1 pixel)
            val image = Toolkit.getDefaultToolkit().createImage(ByteArray(1))
            val trayIcon = TrayIcon(image, "Reminder")
            trayIcon.isImageAutoSize = true
            
            tray.add(trayIcon)
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO)
            
            // Удаляем иконку после отправки
            Thread.sleep(1000)
            tray.remove(trayIcon)
            
            logger.debug("Windows notification sent via SystemTray")
        } catch (e: Exception) {
            logger.warn("Failed to send Windows notification", e)
        }
    }
    
    /**
     * Определение операционной системы
     */
    private fun detectOS(): OS {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("mac") -> OS.MACOS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            else -> {
                logger.warn("Unknown OS: $osName, defaulting to Linux")
                OS.LINUX
            }
        }
    }
}

/**
 * Поддерживаемые операционные системы
 */
enum class OS {
    LINUX,
    MACOS,
    WINDOWS
}
