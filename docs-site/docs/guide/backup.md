# Backup & Restore

"Settings → Backup & Restore" can export your app configuration, chat history, credentials, and workspace files into a single encrypted archive, and restore from a backup.

## Export

At the top of the page, check the scope to export: AI providers, Git credentials, connections & sync, chat history, MCP servers, authorization rules, app settings, workspace files. Your selections are remembered and reused next time.

**Workspace files are off by default**; turn them on manually when needed. This packs all internal workspace files of local mode, including the `.git` directory, so repository history and unpushed commits restore completely. External workspaces you added via "Add local directory" are original directories on your device and are not packed. The exclusion rules are each workspace's own `.gitignore` plus the sync feature's default ignore list (`node_modules`, `build`, `dist`, etc.); symbolic links are not backed up.

::: tip Prefer Git for backing up code
A Git repository already preserves your code and history completely. The export here is better used as an extra fallback, not your primary code backup method.
:::

After tapping "Export Backup", a dialog asks you to set a password:

- **Enter a password**: encrypted with AES-GCM.
- **Leave empty**: outputs a plaintext tar.gz.

The exported archive contains credentials such as API keys; save it to a location you choose via the system file picker.

::: danger Remember your encryption password
An encrypted backup cannot be decrypted and restored if the password is lost. There is no backdoor or recovery path.
:::

## Import

Restore from a backup file using a **merge approach** — existing data is not cleared. Enter the corresponding password; leave it empty for unencrypted backups.

If the backup contains workspace files, a "Restore Workspaces" selection dialog pops up first, listing each workspace in the backup (name and file count) so you can choose which ones to restore.

::: warning Restoring a workspace overwrites directly
A checked workspace overwrites the files in the local workspace of the same name, and this cannot be undone. If no local workspace with the same name exists, one is created automatically and then written to, so changing devices or reinstalling doesn't lose data. Workspaces in remote mode (files on a server) don't participate in restore.
:::

After import completes, a summary list of the restored data is shown: how many providers, Git credentials, remote connections, remote mounts, chat sessions, chat messages, to-do items, MCP servers, global authorization rules, and workspace files were restored.

Chat sessions are placed back into the workspace they belonged to at backup time: if a local workspace with the same name exists, they restore into it directly; if not, an empty workspace is created automatically and the sessions attached to it (consistent with the auto-create logic of "Restore Workspaces"), so after changing devices or reinstalling, each workspace's sessions don't all pile into one place.

## Additional Notes

The backup password only applies to the exported backup file itself. Sensitive configuration stored inside the app — API keys, connection passwords, SSH private keys, etc. — is separately encrypted and stored by the system keystore (Android Keystore), independent of the backup password. When changing devices, migrate via backup import; copying the app data directory directly will fail to decrypt due to key mismatch.
