package kg.forester.sg.service

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kg.forester.sg.data.Wideband
import kg.forester.sg.ssm.OpenPort13
import kg.forester.sg.ssm.OpenPort2
import kg.forester.sg.ssm.Slcan

/**
 * Who may open which USB device. Without the USB wideband nothing is reserved and every loop works as it always
 * did; with it, only the device found to be its adapter (see [kg.forester.sg.data.WidebandPick]) is kept for it,
 * and one being listened to for a moment.
 */
object UsbDeviceRegistry {
    const val ECU = "ecu"
    const val BOX = "sensorbox"
    const val SCREEN = "screen"
    const val INNOVATE = "innovate"
    const val FLASH = "flash"
    val ownership = UsbOwnership()

    /** The serial number, null while Android doesn't let us read it (no permission yet). */
    fun serial(d: UsbDevice): String? = runCatching { d.serialNumber.orEmpty() }.getOrNull()

    /** Kept for the wideband (its adapter, or one it is listening to): the ECU loop and the firmware update leave it alone. */
    fun isWideband(d: UsbDevice): Boolean = ownership.reservedFor(d.deviceName) == INNOVATE

    /** Cables the app knows as ECU adapters (Tactrix OpenPort 1.3 and 2.0, CANable): never offered for the wideband. */
    fun isEcuAdapter(d: UsbDevice): Boolean {
        val name = runCatching { d.productName }.getOrNull()
        return OpenPort13.matches(d.vendorId, d.productId) || OpenPort2.matches(d.vendorId, d.productId) ||
            Slcan.matches(d.vendorId, d.productId, name) || name?.contains("OpenPort", ignoreCase = true) == true
    }

    /**
     * A firmware update has [device] (or its bootloader) to itself: nobody new opens it. Whoever has it open lets go
     * the way it always did (the screen and the box loops are paused for the update), nothing is closed under them.
     */
    fun flash(device: String, on: Boolean) {
        if (on) ownership.reserve(device, FLASH, interrupt = false)
        else {
            ownership.unreserve(device, FLASH)
            if (device == Wideband.device) ownership.reserve(device, INNOVATE)
        }
    }

    fun available(d: UsbDevice, owner: String): Boolean = ownership.available(d.deviceName, owner)

    fun claim(d: UsbDevice, owner: String): UsbOwnership.Lease? = ownership.claim(d.deviceName, owner)

    /** USB-serial adapters the wideband can be on: the usual chips, without the ECU adapters, screens and Arduinos. */
    fun drivers(usb: UsbManager): List<UsbSerialDriver> {
        val known = SensorBoxLink.drivers(usb)
        val names = known.map { it.device.deviceName }.toSet()
        return (known + usb.deviceList.values.filter { it.deviceName !in names }.mapNotNull { d ->
            runCatching {
                when (d.vendorId) {
                    0x0403 -> FtdiSerialDriver(d)
                    0x067B -> ProlificSerialDriver(d)
                    0x1A86 -> Ch34xSerialDriver(d)
                    0x10C4 -> Cp21xxSerialDriver(d)
                    else -> null
                }
            }.getOrNull()
        }).filter { d ->
            val dev = d.device
            d.ports.isNotEmpty() && !isEcuAdapter(dev) && !ScreenLink.isScreen(dev.vendorId) && !SensorBoxLink.isArduino(dev.vendorId)
        }
    }
}
