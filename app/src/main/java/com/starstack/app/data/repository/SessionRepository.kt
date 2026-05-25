package com.starstack.app.data.repository

import com.starstack.app.data.model.Session

interface SessionRepository {
    suspend fun createSession(session: Session): Boolean
    suspend fun getSession(sessionId: String): Session?
    suspend fun getAllSessions(): List<Session>
    suspend fun updateSession(session: Session): Boolean
    suspend fun deleteSession(sessionId: String): Boolean
}
