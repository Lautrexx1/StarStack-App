package com.starstack.app.data.repository

import com.starstack.app.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class SessionRepositoryImpl : SessionRepository {

    private val sessions = ConcurrentHashMap<String, Session>()
    private val _sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    val sessionsFlow = _sessionsFlow.asStateFlow()

    init {
        updateSessionsFlow()
    }

    override suspend fun createSession(session: Session): Boolean {
        return if (sessions.putIfAbsent(session.id, session) == null) {
            updateSessionsFlow()
            true
        } else {
            false
        }
    }

    override suspend fun getSession(sessionId: String): Session? {
        return sessions[sessionId]
    }

    override suspend fun getAllSessions(): List<Session> {
        return sessions.values.toList()
    }

    override suspend fun updateSession(session: Session): Boolean {
        return if (sessions.containsKey(session.id)) {
            sessions[session.id] = session
            updateSessionsFlow()
            true
        } else {
            false
        }
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        return if (sessions.remove(sessionId) != null) {
            updateSessionsFlow()
            true
        } else {
            false
        }
    }

    private fun updateSessionsFlow() {
        _sessionsFlow.value = sessions.values.toList()
    }
}
