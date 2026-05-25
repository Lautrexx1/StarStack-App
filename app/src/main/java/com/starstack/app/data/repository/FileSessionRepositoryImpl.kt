package com.starstack.app.data.repository

import android.content.Context
import com.starstack.app.data.model.Session
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

class FileSessionRepositoryImpl(private val context: Context) : SessionRepository {

    private val SESSIONS_DIR = "StarStackSessions"
    private val SESSIONS_FILE = "sessions.json"
    private val gson = Gson()

    private fun getSessionsFile(): File {
        val appFilesDir = context.filesDir
        val sessionsDir = File(appFilesDir, SESSIONS_DIR)
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs()
        }
        return File(sessionsDir, SESSIONS_FILE)
    }

    private suspend fun readSessionsFromFile(): MutableMap<String, Session> = withContext(Dispatchers.IO) {
        val sessionsFile = getSessionsFile()
        if (!sessionsFile.exists()) {
            return@withContext mutableMapOf()
        }
        try {
            FileReader(sessionsFile).use { reader ->
                val type = object : TypeToken<MutableMap<String, Session>>() {}.type
                gson.fromJson(reader, type) ?: mutableMapOf()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            mutableMapOf()
        }
    }

    private suspend fun writeSessionsToFile(sessionsMap: Map<String, Session>) = withContext(Dispatchers.IO) {
        val sessionsFile = getSessionsFile()
        try {
            FileWriter(sessionsFile).use { writer ->
                gson.toJson(sessionsMap, writer)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override suspend fun createSession(session: Session): Boolean {
        val sessionsMap = readSessionsFromFile()
        return if (sessionsMap.putIfAbsent(session.id, session) == null) {
            writeSessionsToFile(sessionsMap)
            true
        } else {
            false
        }
    }

    override suspend fun getSession(sessionId: String): Session? {
        return readSessionsFromFile()[sessionId]
    }

    override suspend fun getAllSessions(): List<Session> {
        return readSessionsFromFile().values.toList()
    }

    override suspend fun updateSession(session: Session): Boolean {
        val sessionsMap = readSessionsFromFile()
        return if (sessionsMap.containsKey(session.id)) {
            sessionsMap[session.id] = session
            writeSessionsToFile(sessionsMap)
            true
        } else {
            false
        }
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        val sessionsMap = readSessionsFromFile()
        return if (sessionsMap.remove(sessionId) != null) {
            writeSessionsToFile(sessionsMap)
            true
        } else {
            false
        }
    }
}
