package kg.forester.sg.service

/**
 * Who has which USB device open (the ECU loop, the SensorBox, a colour screen, the wideband), by device name, so a
 * multi-port adapter counts once. A reservation keeps a device for one owner; a lease only ever releases itself.
 */
class UsbOwnership {
    private val owners = HashMap<String, Lease>()
    private val reservations = HashMap<String, String>()

    inner class Lease internal constructor(val device: String, val owner: String) : AutoCloseable {
        /** Closes the holder's connection when the device is reserved for somebody else (see [reserve]). */
        @Volatile var interrupt: (() -> Unit)? = null
        val permitted: Boolean get() = synchronized(this@UsbOwnership) {
            owners[device] === this && (reservations[device] == null || reservations[device] == owner)
        }
        override fun close() = synchronized(this@UsbOwnership) {
            if (owners[device] === this) owners.remove(device)
            Unit
        }
    }

    @Synchronized fun available(device: String, owner: String) = device !in owners &&
        (reservations[device] == null || reservations[device] == owner)

    @Synchronized fun claim(device: String, owner: String): Lease? =
        if (!available(device, owner)) null else Lease(device, owner).also { owners[device] = it }

    /** Who has [device] open now, if anybody. */
    @Synchronized fun holder(device: String): String? = owners[device]?.owner

    @Synchronized fun reservedFor(device: String): String? = reservations[device]

    /**
     * Keeps [device] for [owner] (null: for nobody any more). With [interrupt] another holder's connection is closed
     * at once; without it the holder notices [Lease.permitted] and lets go by itself.
     */
    fun reserve(device: String, owner: String?, interrupt: Boolean = true) {
        val close = synchronized(this) {
            if (owner == null) reservations.remove(device) else reservations[device] = owner
            if (interrupt) owners[device]?.takeIf { owner != null && it.owner != owner }?.interrupt else null
        }
        // closing a USB connection never runs under the lock
        close?.invoke()
    }

    /** Drops [owner]'s reservation of [device]; somebody else's stays. */
    @Synchronized fun unreserve(device: String, owner: String) {
        if (reservations[device] == owner) reservations.remove(device)
    }

    /** Devices reserved for [owner]. */
    @Synchronized fun reservedBy(owner: String): Set<String> = reservations.filterValues { it == owner }.keys.toSet()
}
