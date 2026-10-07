package app.anglerfish.nat

interface TunWriter {
    fun write(packet: ByteArray)
}
