package com.callguard.app.ai
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate post-call annotation; never rewrites CallRecord or influences PolicyEngine. */
class AiExplanationStore(context:Context) {
    private val directory=File(context.noBackupFilesDir,"ai-explanations-v1")
    private fun file(callId:String):File {
        require(UUID.fromString(callId).toString()==callId)
        return File(directory,"$callId.json")
    }
    fun read(callId:String):JSONObject?=runCatching {JSONObject(AtomicFile(file(callId)).openRead().use {it.readBytes().toString(Charsets.UTF_8)})}.getOrNull()
    fun save(callId:String,value:JSONObject) {
        check(directory.exists() || directory.mkdirs())
        val atom=AtomicFile(file(callId));val output=atom.startWrite()
        try {output.write(value.toString().toByteArray(Charsets.UTF_8));atom.finishWrite(output)}
        catch(error:Exception) {atom.failWrite(output);throw error}
    }
}
