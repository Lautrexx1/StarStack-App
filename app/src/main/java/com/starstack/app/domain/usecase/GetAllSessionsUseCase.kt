package com.starstack.app.domain.usecase

import com.starstack.app.data.model.Session
import com.starstack.app.data.repository.SessionRepository

class GetAllSessionsUseCase(private val sessionRepository: SessionRepository) {
    suspend operator fun invoke(): List<Session> {
        return sessionRepository.getAllSessions()
    }
}
