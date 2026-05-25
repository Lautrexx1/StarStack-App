package com.starstack.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starstack.app.data.model.Session
import com.starstack.app.data.repository.SessionRepositoryImpl
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for managing astrophotography sessions.
 * Exposes a reactive list of sessions and provides CRUD operations.
 */
class SessionViewModel(private val sessionRepository: SessionRepositoryImpl) : ViewModel() {

    /** Observable list of all sessions, sorted by timestamp descending. */
    val sessions: StateFlow<List<Session>> = sessionRepository.sessionsFlow

    /**
     * Creates a new session with the given target name.
     * The session directory structure is created on disk automatically.
     */
    fun createSession(targetName: String) {
        viewModelScope.launch {
            val session = Session(
                id = UUID.randomUUID().toString(),
                targetName = targetName,
                timestamp = System.currentTimeMillis(),
                rootPath = "" // FileSessionRepository will resolve the real path
            )
            sessionRepository.createSession(session)
        }
    }

    /** Creates a session from a fully constructed Session object. */
    fun createSession(session: Session) {
        viewModelScope.launch {
            sessionRepository.createSession(session)
        }
    }

    /** Deletes a session by its object reference. */
    fun deleteSession(session: Session) {
        viewModelScope.launch {
            sessionRepository.deleteSession(session.id)
        }
    }

    /** Deletes a session by its ID string. */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
        }
    }

    /** Updates an existing session (e.g., after adding frames). */
    fun updateSession(session: Session) {
        viewModelScope.launch {
            sessionRepository.updateSession(session)
        }
    }
}
