package com.aicode.feature.agent.domain.command

import dagger.Module
import dagger.Binds
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Slash command multibinding: collects each built-in [ActionCommandHandler] implementation into a Set,
 * for constructor injection into [SlashCommandRegistry] (skills are read separately by the registry from SkillRepository).
 *
 * When adding a built-in command, just append one `@Binds @IntoSet` binding line here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SlashCommandModule {

    @Binds
    @IntoSet
    abstract fun bindCompressCommandHandler(handler: CompressCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindInitCommandHandler(handler: InitCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindUsageCommandHandler(handler: UsageCommandHandler): ActionCommandHandler
}
