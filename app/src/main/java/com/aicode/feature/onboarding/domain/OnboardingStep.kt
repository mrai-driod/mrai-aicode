package com.aicode.feature.onboarding.domain

import androidx.annotation.StringRes
import com.aicode.R

/**
 * The complete simulated walkthrough flow for the first-launch, end-to-end onboarding:
 *
 * 1. [OPEN_SIDEBAR]: On the home screen, focuses the hamburger button in the top-left corner and prompts to open the sidebar.
 * 2. [ENTER_SETTINGS]: With the sidebar expanded, focuses the "Settings" item at the bottom and prompts to enter system settings.
 * 3. [CONFIG_PROVIDER]: On the Settings page, focuses the "AI Provider" management row and prompts to enter the provider list.
 * 4. [PROVIDER_ADD]: In the provider list, focuses the "+" add button in the top bar and prompts to add a provider.
 * 5. [PROVIDER_CONFIG_INFO]: On the provider edit page, focuses the API Key / configuration input area.
 * 6. [PROVIDER_FETCH_MODELS]: On the provider models tab, focuses the "Fetch Models" button.
 * 7. [SIMULATE_FETCH_DIALOG]: Simulates the fetch-models dialog on the overlay, showing the fetched models and guiding the user to add one.
 * 8. [OPEN_MODEL_PICKER]: Back on the home screen, focuses the model selection button and prompts to open the panel.
 * 9. [SIMULATE_CHOOSE_MODEL]: Simulates the home-screen model selection panel on the overlay, demonstrating how to choose a model.
 * 10. [SEND_MESSAGE]: On the home screen, focuses the input box at the bottom and prompts to type and send the first message to start a conversation.
 */
enum class OnboardingStep(
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int
) {
    OPEN_SIDEBAR(
        route = "chat",
        titleRes = R.string.onboarding_open_sidebar_title,
        descRes = R.string.onboarding_open_sidebar_desc
    ),
    ENTER_SETTINGS(
        route = "chat",
        titleRes = R.string.onboarding_enter_settings_title,
        descRes = R.string.onboarding_enter_settings_desc
    ),
    CONFIG_PROVIDER(
        route = "settings",
        titleRes = R.string.onboarding_config_provider_title,
        descRes = R.string.onboarding_config_provider_desc
    ),
    PROVIDER_ADD(
        route = "settings",
        titleRes = R.string.onboarding_provider_add_title,
        descRes = R.string.onboarding_provider_add_desc
    ),
    PROVIDER_CONFIG_INFO(
        route = "settings",
        titleRes = R.string.onboarding_provider_config_info_title,
        descRes = R.string.onboarding_provider_config_info_desc
    ),
    PROVIDER_FETCH_MODELS(
        route = "settings",
        titleRes = R.string.onboarding_provider_fetch_models_title,
        descRes = R.string.onboarding_provider_fetch_models_desc
    ),
    SIMULATE_FETCH_DIALOG(
        route = "settings",
        titleRes = R.string.onboarding_provider_select_fetched_model_title,
        descRes = R.string.onboarding_provider_select_fetched_model_desc
    ),
    OPEN_MODEL_PICKER(
        route = "chat",
        titleRes = R.string.onboarding_open_model_picker_title,
        descRes = R.string.onboarding_open_model_picker_desc
    ),
    SIMULATE_CHOOSE_MODEL(
        route = "chat",
        titleRes = R.string.onboarding_choose_model_in_sheet_title,
        descRes = R.string.onboarding_choose_model_in_sheet_desc
    ),
    SEND_MESSAGE(
        route = "chat",
        titleRes = R.string.onboarding_send_message_title,
        descRes = R.string.onboarding_send_message_desc
    );

    /** The index of the current step (1-based). */
    val stepIndex: Int get() = ordinal + 1

    /** Whether this is the last step. */
    val isLastStep: Boolean get() = this == entries.last()

    companion object {
        val totalSteps: Int get() = entries.size
        val all: List<OnboardingStep> = entries
    }
}
