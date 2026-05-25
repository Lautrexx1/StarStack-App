package com.starstack.app.domain.usecase

import com.starstack.app.data.model.Session
import com.starstack.app.data.repository.SessionRepository

class CreateSessionUseCase(private val sessionRepository: SessionRepository) {
    suspend operator fun invoke(session: Session): Boolean {
        return sessionRepository.createSession(session)
    }
}
