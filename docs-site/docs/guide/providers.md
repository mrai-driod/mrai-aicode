# AI Providers & Models

AiCode doesn't provide models itself; you need to connect a model service first. The entry point is "Settings → AI Providers".

## Adding a Provider

Tap + at the top right to pop up the add panel from the bottom:

- The first item is **Custom Provider** (manual configuration from blank).
- Below are the built-in official presets; selecting one automatically fills in the name, upstream format, and Base URL — just add the API key on the edit page to use it. Each item can also be modified manually.

The preset list is updated with versions; refer to what's actually displayed.

When customizing, fill in these four items:

- **Name**: an easily recognizable alias, e.g. "My Relay". The list automatically matches a brand icon by name.
- **Upstream format**: one of `OpenAI` / `Anthropic` / `Gemini`, deciding the request protocol. For third-party relay stations, generally choose `OpenAI`.
- **API Key**: the key, in the form `sk-xxx`. Extra spaces or newlines included when pasting are cleaned automatically on save.
- **Base URL**: the API root address. Left empty, the official address is filled in automatically per the upstream format; for a third-party relay, fill in the relay service's address — with or without `/v1` both work.

After configuring, switch to the "Models" tab to fetch models, and you can return to the home page and start chatting.

## Managing the Provider List

Each row of the list shows the name, upstream format, model count, and enabled state.

- Tap a row to enter the edit page.
- **Swipe a row left** to reveal the red delete button.
- **Long-press the name or upstream format area and drag** to adjust the order, which persists after restart.

## Multi-Key Mode

::: tip Version note
Multi-key mode is available from 1.11.0.
:::

If you hold multiple keys for the same service, you can turn on **Multi-Key Mode** in the edit page's "Options": when a key is unavailable, it automatically switches to the next one and **resends this request**. Once on, the single-key input box is hidden, and keys are maintained uniformly on the "Multi-Key Management" page (the one originally filled in becomes the first item in the list).

The management page has three sections:

**Key list**: each key is a collapsible card; when collapsed it shows the index and a masked preview; tap to expand to edit that key and set its **scheduling weight** (default `1`, maximum `1,000,000`; `≤ 0` means kept but not participating in scheduling). Swipe left to delete; long-press the card header to drag and sort (persists after restart); the eye icon toggles plain text / masked. Weight only affects the **round-robin** strategy — the larger the value, the higher the proportion of new sessions assigned to that key (smoothly distributed by weight, e.g. `1:3` is roughly 1 to 3).

**Retrieval strategy**:

- `Sequential`: always use the earlier key; switch to the next only after failures reach the threshold.
- `Round-robin`: new sessions start in rotation by weight proportion, to balance the quota consumption of multiple keys.

To preserve the server-side prompt cache, **the same key is kept within a single session by default**, switching only when the current key is unavailable.

**Failure switching**:

- `Auto-switch status codes`: when these HTTP status codes are hit, switch immediately and resend; default `401,402,403,429`; leave empty to use the defaults, or add/remove as needed (comma-separated).
- `Cooldown duration`: how long a switched-away key waits before rejoining the candidates; default 5 minutes, can also be set to no cooldown.

One request tries the keys in the list in order, and only errors when all fail (prompting "All N keys failed"). If a streaming reply has already started outputting, it isn't resent (to avoid duplicate output); only the key used for subsequent requests is switched. When a switch is triggered, a "Switched to key N of M" hint appears in the chat area.

Authentication failure, insufficient permission, rate limiting, and exhausted balance or quota are considered "key unavailable" and trigger a switch; server 5xx, timeouts, and unreachable network can't be solved by switching keys, so they still follow the usual network retry. If all keys are cooling down, it errors directly and prompts you to retry later.

After the app restarts, it returns to the first key, and cooldown records are cleared.

## Options

The edit page's "Options" group also contains:

- **Full URL**: when on, the Base URL is the complete request address, and default paths are no longer appended automatically.
- **New protocol endpoints**: when the upstream format is OpenAI, you can enable "Response API (new)"; when Gemini, you can enable "Interactions API (new)". Off by default; enable only after confirming the service supports the corresponding new protocol.

### Custom Headers

Appends headers to all requests of this provider, completely overriding default headers of the same name (e.g. filling `User-Agent` replaces the default UA). Only a few relay gateways need this. Placeholders available in values:

::: v-pre
| Placeholder | Description |
| :--- | :--- |
| `{{SESSION_ID}}` | Current session id |
| `{{API_KEY}}` | The key actually used this time |
:::

### Custom Panel Scripts

Lets a provider display a balance or usage card above the input box. Scripts are placed under `~/.aicode/scripts/`, supporting Python, Bash, and Node; you can tap "Run Test" on the edit page to see the output.

Script parameters are injected as `AICODE_KEY_<KEY>` environment variables (KEY uppercased, keeping only letters, digits, and underscores). Placeholders available in values:

::: v-pre
| Placeholder | Description |
| :--- | :--- |
| `{{PROVIDER_API_KEY}}` | Currently effective API key |
| `{{PROVIDER_ID}}` | Provider record ID |
| `{{PROVIDER_NAME}}` | Provider name |
| `{{PROVIDER_TYPE}}` | Upstream format (OPENAI / ANTHROPIC / GEMINI) |
| `{{BASE_URL}}` | Provider Base URL |
| `{{DEFAULT_MODEL}}` | Default model |
| `{{SELECTED_MODEL}}` | Currently selected model |
| `{{MODEL}}` | Actually effective model (selected first, then default) |
:::

For the environment variables a script can read (token, session state, workspace, provider, etc.) and the card JSON structure, see [Custom Panels](/advanced/dashboard-cards).

## Model Management

Switch to the "Models" tab of the edit page.

**Fetch models**: tap "Fetch Models" to sync the available list from the server; results are grouped by brand and searchable. Next to each model are `Image` (supports image input) and `Tools` (supports tool calling) tags; after `↑` is the context length, after `↓` is the maximum output. "Add All" at the top right of the dialog adds new models to the current provider in bulk with one tap.

After a successful fetch, the local list is automatically aligned: models that no longer exist remotely are removed from the list (if fetching fails or returns an empty list, the local list isn't touched), and manually added models not in the remote results are also removed. If you don't want this, you can turn off "Automatically remove invalid models after fetching" in [Preferences](/guide/general).

**Batch operations & multi-select**: when the model list is non-empty, a multi-select button appears at the right of the edit page's top bar; tap it to enter batch selection mode:
- **Select all / Deselect all**: check all models or clear with one tap.
- **Batch test**: run connectivity tests on the selected models in turn.
- **Batch delete**: delete in bulk after a second confirmation.
- Tap the back arrow at the top left or press the back key to exit.

**Manual add**: relay station model names are sometimes not fully fetched; tap + to enter one manually. In the edit dialog you can customize the model:

- **Total context window**: the combined capacity of input and output, affecting when automatic context compaction is triggered.
- **Input window**: the maximum input length the model can receive, set independently of the total context window.
- **Output window**: the maximum length of a single reply.
- **Price**: four tiers — input / output / cache read / cache write — in US dollars per million tokens, used for cost statistics.
- **Capability switches**: image input, image output, tool calling, reasoning capability.

Window values must be valid positive integers, or can be left empty to use auto-detected values; entering 0, a negative number, a decimal, or an out-of-range integer shows a prompt and can't be saved. When editing an existing model, changing only the price doesn't change its original total context, input, and output windows.

Manually filled values take precedence; unfilled ones fall back to auto-detection results, and custom settings always correspond to the original model name under the current provider. Auto-detection first tries a full-name match, then tries ignoring case; if still not found, it tries removing common suffixes like `-thinking`, `-preview`, `-high`, and tries removing the provider's brand prefix. Only after all these attempts fail does it remove the last `-` and everything after it and match once — it won't repeatedly shorten the name.

**Test**: each model row has a "Test" button, which sends a real request to verify connectivity; the result is shown below the row.

**Sorting**: long-press the model name area and drag to adjust the order; it takes effect immediately after saving (both the model selection dialog and the default model list follow this order) and persists after restart.

**Delete**: swipe a model row left to delete. If the deleted model is in use (the currently selected model, the new-session default model, dedicated models for image recognition / image generation / compaction / title summary, etc., or a model bound to a historical session), the related selections automatically fall back to the default model.

## Thinking Intensity

The thinking intensity button on the chat input box decides the model's reasoning tier. When the tiers a model supports can be identified, only those tiers are listed; when they can't (the model has no tier information, or a relay station renamed the model), all tiers are listed — `Off` / `Very Low` / `Low` / `Medium` / `High` / `Very High` / `Maximum` — for you to judge. Your selection is remembered and used the next time a new session uses that model.
