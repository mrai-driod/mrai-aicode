package com.aicode.feature.agent.domain.tool.question

/**
 * The request object suspended waiting for the user's answer after the AI calls the ask_user_question tool.
 *
 * @param id The unique identifier of this call (corresponding to ToolCall.id), used to pair on resolve.
 * @param questions The 1-4 questions to ask the user this time.
 */
data class PendingUserQuestion(
    val id: String,
    val questions: List<QuestionItem>
)

/**
 * A single question.
 *
 * @param question The full question text, such as "Which library should be used for HTTP requests?"
 * @param header A short label (<=12 characters), shown as a Chip/Tag, such as "Library" or "Plan".
 * @param options 2-4 preset options (the UI automatically appends an "Other" option).
 * @param multiSelect Whether multiple selection is allowed. false = single-choice RadioButton, true = multi-choice Checkbox.
 */
data class QuestionItem(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false
)

/**
 * A single option.
 *
 * @param label The option label (short, 1-5 words), such as "OkHttp (recommended)".
 * @param description The option description, explaining what choosing it means or its pros and cons.
 */
data class QuestionOption(
    val label: String,
    val description: String
)

/**
 * All of the user's answers to one ask_user_question call.
 *
 * @param answers Each question corresponds to one [SingleAnswer]. If the user chose to add more detail, this list is empty.
 */
data class UserQuestionAnswer(
    val answers: List<SingleAnswer>
)

/**
 * The answer to a single question.
 *
 * @param question The original question text (so the AI can tell which question the answer is for).
 * @param selected The list of option labels the user selected (single choice = 1 element; multiple choice may be several).
 * @param customText If the user selected "Other" and entered custom text, it is stored in this field; otherwise null.
 */
data class SingleAnswer(
    val question: String,
    val selected: List<String>,
    val customText: String? = null
)
