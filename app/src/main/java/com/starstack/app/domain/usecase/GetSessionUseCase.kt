package com.starstack.app.domain.usecase

import com.starstack.app.data.model.Session
import com.starstack.app.data.repository.SessionRepository

class GetSessionUseCase(private val sessionRepository: SessionRepository) {
    suspend operator fun invoke(sessionId: String): Session? {
        return sessionRepository.getSession(sessionId)
    }
}
