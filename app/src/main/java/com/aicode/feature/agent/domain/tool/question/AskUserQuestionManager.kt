package com.aicode.feature.agent.domain.tool.question

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the park-and-resume flow of the ask_user_question tool.
 *
 * Mirrors the design of [com.aicode.feature.agent.domain.tool.ToolPermissionManager]:
 * the tool side calls [awaitAnswer] to suspend; the UI side observes [pendingQuestion] to render the panel;
 * after the user chooses, it calls [resolve] to wake the tool side.
 *
 * Only one pending question is allowed at a time (serialised by a mutex), and it does not interfere with the permission dialog.
 */
@Singleton
class AskUserQuestionManager @Inject constructor() {

    private val requestMutex = Mutex()

    private val _pendingQuestion = MutableStateFlow<PendingUserQuestion?>(null)

    /** The UI observes this StateFlow to decide whether to show the question panel. */
    val pendingQuestion: StateFlow<PendingUserQuestion?> = _pendingQuestion.asStateFlow()

    private var currentId: String? = null
    private var currentDecision: CompletableDeferred<UserQuestionAnswer>? = null

    /**
     * Tool-side call: starts a question request and suspends waiting for the user's answer.
     * Only one request is allowed at a time (serialised by [requestMutex]).
     */
    suspend fun awaitAnswer(question: PendingUserQuestion): UserQuestionAnswer = requestMutex.withLock {
        val decision = CompletableDeferred<UserQuestionAnswer>()
        currentId = question.id
        currentDecision = decision
        _pendingQuestion.value = question

        try {
            decision.await()
        } finally {
            if (currentId == question.id) {
                currentId = null
                currentDecision = null
                _pendingQuestion.value = null
            }
        }
    }

    /**
     * UI-side call: after the user finishes choosing, passes the answer back and wakes the suspended [awaitAnswer].
     */
    @Synchronized
    fun resolve(id: String, answer: UserQuestionAnswer) {
        if (currentId != id) return
        _pendingQuestion.value = null
        currentDecision?.complete(answer)
    }
}
