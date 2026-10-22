# Built-in Browser

The app has a built-in WebView browser supporting AI-automated web page operations. The AI can operate the browser in the background, and screenshots can also be taken in the background (no need to open the panel first).

## Opening the Browser

Tap "Built-in Browser" on the card at the bottom of the sidebar:

- **Large screen (tablet)**: the browser opens in the right column side by side with the chat; drag the divider to adjust the width.
- **Narrow screen (phone)**: the browser opens full-screen; press the back key to return to chat.

## Manual Browsing

Enter a URL in the address bar and press Enter to navigate; when the entered content doesn't look like a URL (e.g. contains spaces or Chinese), it's automatically handed to the built-in search engine Bing to search. On the right of the address bar are the developer tools switch and the refresh button; the bottom bar below contains back, forward, new tab, night mode toggle, and multi-tab management. While a page loads, a linear progress bar is shown below the address bar.

## Local File Preview

The address bar supports local file addresses, so you can directly open HTML files on the device:

- Real device path: `file:///storage/emulated/0/Download/index.html`
- Container path (the path the AI sees inside the container, automatically mapped to the real file): `~/workspace/index.html`, `/etc/xxx.html`

The AI can also open local pages via the `browser` tool's `navigate` and screenshot them for analysis.

**Note**: in remote workspace mode, workspace files are on the remote side, so only the page itself can be loaded; relative resources referenced by the HTML (CSS/JS/images) will fail. Local workspaces are unaffected.

## Developer Tools

Tap the `</>` (code icon) on the right of the address bar to turn the mobile developer tools (based on Eruda) on or off at any time:
- **Full-featured console**: includes Console (view logs and execute JavaScript code), Elements (view and edit the DOM tree and CSS styles in real time), Network (view network requests and responses), Resources (view data such as LocalStorage and cookies), Sources, etc.
- **Instantly available**: after enabling, a floating gear icon appears at the bottom right of the page; tap it to expand the full console panel. Tapping the developer tools button in the address bar again closes it completely and removes the floating ball.
- **Desktop debugging linkage**: when enabled, Chromium's `WebContentsDebugging` is enabled in sync, supporting connecting to a computer via USB and visiting `chrome://inspect` in the Chrome browser for desktop-level remote inspection.

## Night Mode

The browser has a built-in night mode, following the app's appearance by default: when the app is dark, pages automatically darken; when the app is light, they return to normal. The moon/sun icon in the bottom bar can toggle manually; after a manual selection it no longer follows the app's appearance, and when you switch back to match the app's appearance it automatically resumes following.

Night mode uses a dual-track rendering architecture:
- **Native dark preferred**: declares `prefers-color-scheme: dark` and `color-scheme: dark` to the page; modern websites (such as GitHub, Google, etc.) automatically enable their officially fine-tuned native dark themes, with clear text and no inverted dark areas.
- **Darkening bright sites**: for bright sites without dark styles, a translucent dark overlay is placed over the top of the page to darken it overall (preserving the original colors, only reducing brightness, no inversion), for a soft look. This overlay applies to page rendering, and AI screenshots also show the darkened colors.
- **Anti flash-white**: a dark viewport background is set immediately at page startup and the dark preference is injected early, avoiding the white-screen flash at the moment of page refresh.

## AI Automated Operations

The AI can control the browser through the `browser` tool to perform the following operations:

| Operation | Description |
| --- | --- |
| `navigate` | Navigate to a specified URL (supports `http(s)`; local files support `file://` or container paths; input that doesn't look like a URL is searched by keyword with the built-in search engine Bing), waiting for the page to finish loading |
| `evaluate` | Execute arbitrary JavaScript (supports Promise/async, returns native JSON) |
| `click` | Click an element (full event chain, compatible with React/Vue) |
| `fill` | Fill a form field (native setter + React valueTracker hack) |
| `select` | Select a native dropdown `<select>` (by value or visible text) |
| `hover` | Hover an element (dispatches mouseenter/over/move, can expand dropdown menus) |
| `press` | Key press (Enter/Escape/Tab/arrow keys/Backspace/Delete/space/ordinary characters) |
| `getText` | Extract page text (selector optional), with script/style filtered out |
| `getHtml` | Extract page HTML (selector optional) |
| `getBackbone` | Extract the accessibility tree (role/name/ref, `maxDepth` configurable), whose refs can be used directly for subsequent operations |
| `screenshot` | Capture the current page and return the image for visual model analysis (supports background off-screen screenshots) |
| `console` | Get page console logs (filterable by level, clearable) |
| `wait` | Wait for a condition to be met (`text=` / `text*=` / `selector=` / `domStable`, `timeout` configurable) |
| `scroll` | Scroll the page (to a specified element or to the bottom) |
| `dialog` | Handle a suspended `confirm`/`prompt` dialog (accept or cancel) |
| `back` / `forward` / `reload` | Browser navigation control; back/forward wait for navigation to complete |

When a page pops up a `confirm`/`prompt`, it's suspended, and the tool response contains a `pendingDialog` field; use the `dialog` action to accept or cancel (it auto-cancels if not handled within 30 seconds).

## Selector Formats

The selectors of `click`, `fill`, `hover`, `getText`, `getHtml`, `scroll`, `wait` support:

- `ref=e22`: an element reference returned by `getBackbone`, directly locating an element in the snapshot
- CSS: `#id`, `.class`, `a[href=...]`
- `text=Log in`: exact match of element text
- `text*=Log in`: substring match
- `role=button[name="Log in"]`: match by role and name
- `xpath=//a[@href]`: XPath expression

`getBackbone` returns the accessibility tree: each node is shaped like `{role,name,ref,url,value,children}`, where `role` is the accessible role derived from the tag / `role` attribute (`link`/`button`/`textbox`/`heading`/`navigation`…), `name` is the accessible name (`aria-label`/`aria-labelledby`/`alt`/`placeholder`/associated `label`/content text), and `ref` is only assigned to interactive elements. `script`/`style` and invisible elements are filtered out, and nodes beyond `maxDepth` are marked with `truncated:true`.
