package cz.audioreplay.audio

data class CaptureFailure(val message: String, val recoverable: Boolean = true)

interface CaptureListener {
    fun onAvailability(available: Boolean)
    fun onFailure(failure: CaptureFailure)
}

/** The service owns the user's recording intent; the driver owns one AudioRecord. */
interface CaptureDriver {
    fun blockedReason(): String?
    fun start(resumeRate: Int?, listener: CaptureListener): Int
    fun stop()
}
