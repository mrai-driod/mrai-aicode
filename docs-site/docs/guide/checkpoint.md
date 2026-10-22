# Checkpoints & Undo

Before the AI performs a file modification, AiCode automatically saves a snapshot of the files before the change and creates a checkpoint. If the result isn't what you expected or introduces unexpected problems, you can roll back to a chosen point at any time with one tap. The mechanism runs entirely automatically, with no manual intervention needed.

## Rollback Methods

In the chat history, find the **message you sent** that you want to go back to; there's an undo icon on its left. Tapping it opens that checkpoint's action menu.

The menu has three choices:

| Action | Code | Conversation |
| --- | --- | --- |
| Restore code & conversation | Go back to the state before this message | Delete the messages after this one; the original question is filled back into the input box |
| Restore conversation only | Keep as-is, unchanged | Delete the messages after this one |
| Restore code only | Go back to the state before this message | Keep completely unchanged |

The first one is the most commonly used: it's like "redo this round" — code and conversation both return to the starting point, and you can edit the question and send it again.

Restoring the conversation deletes the selected message and all following messages, undoes the existing compression summary, and restores the original conversation preserved before the rollback point, so the summary doesn't carry later facts into what follows. "Restore code only" doesn't change the conversation. If the restored history is too long, it will be re-compressed before the next send; usage from calls that already happened is not zeroed out. Content that an old version already truncated or lost cannot be brought back by a rollback.

If you only want the AI to forget a few rounds of discussion that went off track but keep the code it has already written, use "Restore conversation only". Conversely, if you want to discard the code but keep the discussion for review, use "Restore code only".

## Scope & Limitations

Checkpoints only record changes the AI made **through the file tools**. If a file was changed or deleted by a command in the terminal (`rm`, `mv`, `sed` and the like — whether you typed it yourself or the AI executed it), a checkpoint can't restore it precisely.

In that case, restore through Git instead: as long as the file was once tracked by Git (committed at least once), go to the status tab of the Git page, long-press the file, and choose "Restore file" or "Revert changes" to get it back, regardless of how it was deleted. Conversely, a brand-new file that was never committed isn't covered by this layer of protection.

For important projects, use Git alongside: checkpoints suit rolling back a single round of changes, while Git is the complete version safety net.
