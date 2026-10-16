package cz.audioreplay.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.FileProvider
import cz.audioreplay.core.SegmentStore
import cz.audioreplay.core.RecordingNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.*

class RecordingsRepository(private val context: Context, private val dao: RecordingDao) {
    val flow = dao.observe()
    val staging = File(context.filesDir, "staging").also { it.mkdirs() }
    private val folder = File(context.filesDir, "recordings").also { it.mkdirs() }
    private val journal = AtomicFile(File(context.filesDir, "publication.json"))
    private val mutex = Mutex()
    private val counters = context.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
    private val relativePath = "${if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC}/AudioReplay/"

    suspend fun publish(source: File, snapshot: SegmentStore.Snapshot, bitrate: Int, public: Boolean): Recording =
        withContext(Dispatchers.IO) { mutex.withLock {
            val now = System.currentTimeMillis()
            publishLocked(source, UUID.randomUUID().toString(), allocateName(now), now, snapshot.durationUs, snapshot.sampleRate, bitrate, public)
        } }

    private suspend fun allocateName(now: Long): String {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val used = dao.all().map { it.name }.toSet() + (folder.listFiles()?.map { it.name } ?: emptyList())
        val (name, sequence) = RecordingNames.next(day, used, counters.getInt(day.toString(), 0))
        check(counters.edit().putInt(day.toString(), sequence).commit()) { "Nelze uložit pořadí názvů." }
        return name
    }

    private suspend fun publishLocked(source: File, id: String, name: String, now: Long, durationUs: Long,
        sampleRate: Int, bitrate: Int, public: Boolean, previousLocation: String? = null): Recording {
            check(!journal.baseFile.exists()) { "Předchozí zápis vyžaduje obnovu. Znovu otevřete aplikaci." }
            var uri: Uri? = null
            var result: Recording? = null
            var durable = false
            try {
                val location = if (public) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                    uri.toString()
                } else File(folder, name).absolutePath
                val recording = Recording(id, name, location, public, now, durationUs, source.length(), sampleRate, bitrate)
                result = recording
                writeJournal(recording, previousLocation)
                if (public) {
                    context.contentResolver.openFileDescriptor(uri!!, "w")!!.use { descriptor ->
                        FileOutputStream(descriptor.fileDescriptor).use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                    }
                    check(valid(recording)) { "Kontrola výsledného M4A selhala." }
                    check(context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
                } else {
                    check(source.renameTo(File(location))) { "Soubor se nepodařilo atomicky zveřejnit." }
                    check(valid(recording)) { "Kontrola výsledného M4A selhala." }
                }
                durable = true
                dao.insert(recording)
                source.delete(); journal.delete()
                return recording
            } catch (e: Exception) {
                // Completed media survives a database failure and is recovered from the journal.
                if (!durable) {
                    result?.let { deleteMedia(it) }
                    uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                    journal.delete()
                }
                throw e
            }
    }

    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock {
        if (journal.baseFile.exists()) {
            val json = runCatching { JSONObject(journal.readFully().toString(Charsets.UTF_8)) }.getOrNull()
            val pending = json?.let { runCatching { fromJson(it) }.getOrNull() }
            if (pending != null) {
                if (valid(pending)) {
                    if (pending.publicStorage) context.contentResolver.update(Uri.parse(pending.location), ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0); put(MediaStore.Audio.Media.DISPLAY_NAME, pending.name)
                    }, null, null)
                    dao.insert(pending)
                    json?.optString("previousLocation")?.takeIf { it.isNotBlank() && it != pending.location }?.let { previous ->
                        val file = File(previous)
                        if (file.canonicalFile.parentFile == folder.canonicalFile) file.delete()
                    }
                } else deleteMedia(pending)
            }
            journal.delete()
        }
        staging.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        // Only pending media owned by this package and this app's path is eligible for cleanup.
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.IS_PENDING}=1 AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                arrayOf(context.packageName, relativePath), null)?.use { c ->
                while (c.moveToNext()) context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
            }
        }
        dao.all().forEach { if (!exists(it)) dao.delete(it) }
    } }

    suspend fun rename(recording: Recording, requested: String) = withContext(Dispatchers.IO) { mutex.withLock {
        renameLocked(recording, requested)
    } }
    private suspend fun renameLocked(recording: Recording, requested: String) {
        val base = requested.trim().removeSuffix(".m4a").trim()
        require(base.length in 1..120 && base.none { it.code < 32 || it in "/\\:*?\"<>|" }) { "Zadejte název o délce 1–120 znaků bez lomítek a speciálních znaků." }
        val name = "$base.m4a"
        val target = if (recording.publicStorage) recording.location else File(folder,name).absolutePath
        val changed = recording.copy(name = name, location = target)
        if (!recording.publicStorage) check(target == recording.location || !File(target).exists()) { "Soubor s tímto názvem již existuje." }
        check(!journal.baseFile.exists()) { "Předchozí zápis vyžaduje obnovu. Znovu otevřete aplikaci." }
        writeJournal(changed, if (recording.publicStorage) null else recording.location)
        try {
        val location = if (recording.publicStorage) {
            check(context.contentResolver.update(Uri.parse(recording.location), ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) }, null, null) == 1)
            recording.location
        } else {
            val newFile = File(folder, name)
            check(newFile.absolutePath == recording.location || !newFile.exists()) { "Soubor s tímto názvem již existuje." }
            check(File(recording.location).renameTo(newFile)) { "Přejmenování se nezdařilo." }
            newFile.absolutePath
        }
        dao.update(recording.copy(name = name, location = location))
        journal.delete()
        } catch (e: Exception) {
            val restored = if (recording.publicStorage) runCatching {
                context.contentResolver.update(Uri.parse(recording.location),ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME,recording.name) },null,null) == 1
            }.getOrDefault(false) else File(target).renameTo(File(recording.location))
            if (restored) journal.delete()
            throw e
        }
    }
    /** Only generated v1 names are migrated. User supplied names are preserved. */
    suspend fun compactLegacyNames() = withContext(Dispatchers.IO) { mutex.withLock {
        dao.all().sortedBy { it.createdMs }.filter { RecordingNames.isLegacy(it.name) }.forEach { recording ->
            renameLocked(recording, allocateName(recording.createdMs))
        }
    } }
    suspend fun makePrivateRecordingsPublic() = withContext(Dispatchers.IO) { mutex.withLock {
        dao.all().filter { !it.publicStorage }.forEach { recording ->
            publishLocked(File(recording.location), recording.id, recording.name, recording.createdMs, recording.durationUs,
                recording.sampleRate, recording.bitrate, true, recording.location)
        }
    } }
    fun directoryUri(): Uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
        "primary:${relativePath.trimEnd('/')}")
    fun directoryLabel(): String = relativePath.trimEnd('/')
    suspend fun delete(recording: Recording) = withContext(Dispatchers.IO) { mutex.withLock {
        check(deleteMedia(recording) || !exists(recording)) { "Soubor nelze smazat." }; dao.delete(recording)
    } }
    fun uri(recording: Recording): Uri = if (recording.publicStorage) Uri.parse(recording.location)
        else FileProvider.getUriForFile(context, "${context.packageName}.files", File(recording.location))
    private fun exists(recording: Recording): Boolean = runCatching {
        if (recording.publicStorage) context.contentResolver.openFileDescriptor(Uri.parse(recording.location), "r")?.use { true } ?: false
        else File(recording.location).isFile
    }.getOrDefault(false)
    private fun deleteMedia(recording: Recording): Boolean = runCatching {
        if (recording.publicStorage) context.contentResolver.delete(Uri.parse(recording.location), null, null) > 0
        else File(recording.location).delete()
    }.getOrDefault(false)
    private fun valid(recording: Recording): Boolean {
        val extractor = MediaExtractor()
        return try {
            val length = if (recording.publicStorage) context.contentResolver.openFileDescriptor(Uri.parse(recording.location), "r")?.use { it.statSize } ?: -1 else File(recording.location).length()
            if (length != recording.sizeBytes || length <= 0) false else {
                if (recording.publicStorage) extractor.setDataSource(context, Uri.parse(recording.location), null)
                else extractor.setDataSource(recording.location)
                extractor.trackCount == 1 && extractor.getTrackFormat(0).getString("mime") == "audio/mp4a-latm" &&
                    extractor.getTrackFormat(0).getLong("durationUs") > 0
            }
        } catch (_: Exception) { false } finally { extractor.release() }
    }
    private fun writeJournal(recording: Recording, previousLocation: String? = null) {
        val stream = journal.startWrite()
        try { stream.write(toJson(recording).apply { if (previousLocation != null) put("previousLocation", previousLocation) }.toString().toByteArray()); journal.finishWrite(stream) }
        catch (e: Exception) { journal.failWrite(stream); throw e }
    }
    private fun toJson(r: Recording) = JSONObject().apply {
        put("id", r.id); put("name", r.name); put("location", r.location); put("public", r.publicStorage)
        put("created", r.createdMs); put("duration", r.durationUs); put("size", r.sizeBytes); put("rate", r.sampleRate); put("bitrate", r.bitrate)
    }
    private fun fromJson(j: JSONObject) = Recording(j.getString("id"), j.getString("name"), j.getString("location"), j.getBoolean("public"), j.getLong("created"), j.getLong("duration"), j.getLong("size"), j.getInt("rate"), j.getInt("bitrate"))
}
