# Workspace Sync

Sync a workspace directory on your phone to a remote server. The entry point is "Settings → Runtime Environment → Connections & Sync"; the page has four tabs at the bottom.

::: tip Division of labor between this page and "Containers & Images"
This page only handles **connection channels and file sync**. If you want the AI to directly use a remote server as the execution environment (commands, files, and terminal all running on the server), that's a different feature — see [Remote SSH Mode](/guide/remote-ssh), which reuses the SFTP channel built here.
:::

## Connection Configuration

Tap + at the top right to create a new connection channel:

- **Protocol type**: `SFTP` (default port 22) or `FTP` (default port 21).
- **Connection name**: an alias.
- **Host address**: IP or domain name.
- **Port / username / authentication method**: required. The authentication method can be **password**, or **key** (choose from private keys already added on the "SSH Keys" page; the passphrase for an encrypted private key is filled in on the key edit page — the connection doesn't ask for it again).
- **Test connectivity**: you can test before saving.

### SSH Keys & Host Fingerprints

Tap the key icon in the top bar to enter; there are two tabs at the bottom:

**Keys**: manages the private keys used for login. Tap + at the top right — there are two ways to add: **Import from file** (choose a private key file from the file picker) or **Paste key** (enter the same form as the edit page; paste the private key content and fill in the name and passphrase; leaving the name empty auto-names it). The private key is encrypted, copied to the private directory, and its public key fingerprint is parsed out; encrypted private keys are marked "Passphrase required". Tap a key to enter the edit page: you can change the name and passphrase (for encrypted private keys); the private key content is read-only (hidden by default; tap the eye on the right to view). The passphrase is set on the key, and when a connection uses that key it comes along automatically. Swipe left to delete.

**Fingerprints**: manages trusted server host fingerprints. The first time you connect to a server, a dialog shows the key type and `SHA256:` fingerprint; after you confirm "Trust and connect", it's saved and let through directly afterwards. If a server's fingerprint changes, confirmation is required again — this is the mechanism against man-in-the-middle attacks, so be careful when a fingerprint suddenly changes. Swipe left to delete, and you'll confirm again on the next connection.

## Mount Directories

A connection channel only solves "can it connect"; you also need to establish the mapping of "which local workspace corresponds to which remote directory". Tap + at the top right:

- **Associated connection channel**: dropdown to select an already-created connection.
- **Remote target directory**: the folder icon on the right opens the remote directory browser to pick level by level.
- **Map to local workspace**: dropdown to select a local workspace (you need to create one in the sidebar first).
- **Auto-connect at app startup**: when on, it follows the current workspace — when the app starts or you switch workspaces, it automatically connects the mount mapped to the current workspace and watches local changes, while disconnecting other workspaces' mounts. Connection failures retry continuously at backoff intervals and only stop when the switch is turned off.

When saving, if an identical "channel + remote directory + workspace" combination already exists, it prompts that the mount already exists and blocks the save.

After a successful connection, the card has several actions: disconnect, upload all / sync all, download all, connect and sync. Swipe a list row left to delete.

::: danger Sync is one-way
Auto sync only **pushes local changes made after connecting to the remote** (watching local file additions, deletions, and modifications); changes in the remote directory are **not** pulled down automatically. Connecting doesn't automatically upload existing files either; tap "Upload All" manually when needed.

When you need to pull remote files to local, tap "Download All" manually — but note this is a **full overwrite-style** pull: it overwrites local files of the same name (without deleting extra local files), and unsaved local changes may be overwritten. Confirm clearly before using.
:::

The connection timeout is 15 seconds; when the host is unreachable it errors quickly rather than waiting forever. A connected mount probes liveness every 15 seconds; when disconnected, the card shows "Reconnecting" and automatically reconnects at intervals of 5, 10, 20, 40, 60 seconds, resuming sync after success.

## Built-in FTP Server

Start an FTP service on your phone to manage projects on it from a computer.

Your phone and computer must be on the same WiFi. After starting, the interface shows the FTP address (e.g. `ftp://192.168.1.100:2121`; tap the address to copy it); access it from your computer's file explorer or FileZilla, and you can manage all projects under `projects/`.

Configurable parameters are the listen port, login username, login password, whether anonymous access is allowed, and whether to start at boot. After changing, tap "Save Configuration" at the bottom.

::: warning Watch out for security
Allowing anonymous access means any device on the same network can read and write your project files. Don't enable anonymous access on public WiFi, and it's recommended to turn the service off after use.
:::

## Sync Settings

- **Follow .gitignore rules**: when on, automatically reads the `.gitignore` in the project root to filter files to sync.
- **Maximum sync queue size per batch**: prevents a large number of file changes at once from overloading the server with requests or congesting the network; excess operations are queued and executed in batches.

Additionally, the sync engine always ignores common build and cache directories (`node_modules`, `.git`, `build`, `dist`, etc.), with no manual configuration needed.

After changing, tap "Save Settings" at the bottom to take effect.
