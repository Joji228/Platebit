package app.morsel.ui.scan

/**
 * The volume keys as a shutter, like the camera app. The Scan screen sets [onPress] while it's
 * showing and clears it when it leaves, so the keys change the volume everywhere else as usual.
 */
object HardwareShutter {
    @Volatile
    var onPress: (() -> Unit)? = null
}