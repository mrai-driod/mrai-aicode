package com.aicode.feature.agent.domain.tool.question

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * Lets the AI ask the user structured multiple-choice questions.
 *
 * When the AI hits a scenario requiring a user decision (choosing a library, choosing a plan, confirming an environment install, etc.), calling this tool can pause execution,
 * pop up a question panel in the UI, wait for the user to choose, then continue working with the user's answer.
 *
 * Each call can ask 1-4 questions at once, each with 2-4 preset options, supporting single / multiple choice.
 * On the user side, each question automatically gets an extra "Other" option; selecting it allows entering custom text.
 */
class AskUserQuestionTool @Inject constructor(
    private val questionManager: AskUserQuestionManager
) : AgentTool() {

    private companion object {
        const val TAG = "AskUserQuestionTool"
        const val MAX_QUESTIONS = 4
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 4
    }

    override val name = "askUserQuestion"
    override val capabilities = setOf(ToolCapability.USER_INTERACTION)

    override val description =
        "Ask the user structured multiple-choice questions and block waiting for their selection, for scenarios requiring a user decision. Can ask 1-4 questions at once, with 2-4 options per question (the UI automatically appends an 'Other' option)."

    /** JSON Schema of a single option */
    private val optionItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "label" to mapOf(
                "type" to "string",
                "description" to "Option display text (short, 1-5 words), e.g. 'OkHttp (recommended)'."
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Option description, explaining what choosing it means or its pros and cons."
            )
        ),
        "required" to listOf("label", "description")
    )

    /** JSON Schema of a single question */
    private val questionItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "question" to mapOf(
                "type" to "string",
                "description" to "The full question text; should be clear, specific, and end with a question mark."
            ),
            "header" to mapOf(
                "type" to "string",
                "description" to "Short label (<=12 characters), shown as a tag header, e.g. 'Plan', 'Library', 'Tool'."
            ),
            "options" to mapOf(
                "type" to "array",
                "description" to "2-4 preset options. The UI automatically appends an 'Other' option for free-form input." +
                    "If you have a recommended option, put it first and add '(recommended)' at the end of its label.",
                "items" to optionItemSchema
            ),
            "multiSelect" to mapOf(
                "type" to "boolean",
                "description" to "Whether to allow multiple selections. Defaults to false (single selection). When allowing multiple selections, phrase the question accordingly."
            )
        ),
        "required" to listOf("question", "header", "options")
    )

    override val parameters = mapOf(
        "questions" to ToolParameter(
            name = "questions",
            type = ParameterType.ARRAY,
            description = "List of questions to ask the user (1-4 questions). Each question contains question, header, options, and an optional multiSelect.",
            required = true,
            itemsSchema = questionItemSchema
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val questionsJson = args["questions"]?.jsonArray
        if (questionsJson == null || questionsJson.isEmpty()) {
            return ToolResult.Error("Missing required parameter: questions (must contain at least one question)", "MISSING_QUESTIONS")
        }

        if (questionsJson.size > MAX_QUESTIONS) {
            return ToolResult.Error(
                "At most $MAX_QUESTIONS questions can be asked at once; currently ${questionsJson.size}",
                "TOO_MANY_QUESTIONS"
            )
        }

        // Parse each question
        val questions = mutableListOf<QuestionItem>()
        for ((idx, qElement) in questionsJson.withIndex()) {
            val qObj = qElement.jsonObject

            val questionText = qObj["question"]?.jsonPrimitive?.contentOrNull?.trim()
            if (questionText.isNullOrEmpty()) {
                return ToolResult.Error("Question ${idx + 1} is missing the question field", "INVALID_QUESTION")
            }

            val header = qObj["header"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""

            val optionsJson = qObj["options"]?.jsonArray
            if (optionsJson == null || optionsJson.size < MIN_OPTIONS) {
                return ToolResult.Error(
                    "Question ${idx + 1} needs at least $MIN_OPTIONS options",
                    "TOO_FEW_OPTIONS"
                )
            }
            if (optionsJson.size > MAX_OPTIONS) {
                return ToolResult.Error(
                    "Question ${idx + 1} allows at most $MAX_OPTIONS options; currently ${optionsJson.size}",
                    "TOO_MANY_OPTIONS"
                )
            }

            val options = optionsJson.map { optElement ->
                val optObj = optElement.jsonObject
                QuestionOption(
                    label = optObj["label"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
                    description = optObj["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                )
            }.filter { it.label.isNotEmpty() }

            if (options.size < MIN_OPTIONS) {
                return ToolResult.Error(
                    "Question ${idx + 1} has fewer than $MIN_OPTIONS valid options",
                    "INVALID_OPTIONS"
                )
            }

            val multiSelect = qObj["multiSelect"]?.jsonPrimitive?.let {
                runCatching { it.boolean }.getOrNull()
            } ?: false

            questions.add(
                QuestionItem(
                    question = questionText,
                    header = header,
                    options = options,
                    multiSelect = multiSelect
                )
            )
        }

        val requestId = UUID.randomUUID().toString()
        val pending = PendingUserQuestion(id = requestId, questions = questions)

        FileLogger.d(TAG, "ask_user_question started: ${questions.size} questions, waiting for the user to answer...")

        // Suspend, waiting for the user to choose in the UI
        val answer = questionManager.awaitAnswer(pending)

        FileLogger.d(TAG, "ask_user_question user answered: ${answer.answers.size} answers")

        // Serialise the user's answers to text and feed them back to the model
        val resultText = buildString {
            if (answer.answers.isEmpty()) {
                append("The user did not select a preset option and will provide additional explanation. Continue based on what the user adds next.")
            } else {
                for (a in answer.answers) {
                    append("\"${a.question}\" = ")
                    val parts = mutableListOf<String>()
                    if (a.selected.isNotEmpty()) {
                        parts.addAll(a.selected)
                    }
                    if (a.customText != null) {
                        parts.add("Other: ${a.customText}")
                    }
                    if (parts.isEmpty()) {
                        append("(not selected)")
                    } else {
                        append(parts.joinToString(", "))
                    }
                    append("\n")
                }
            }
        }.trim()

        return ToolResult.Success(JsonPrimitive(resultText))
    }
}
