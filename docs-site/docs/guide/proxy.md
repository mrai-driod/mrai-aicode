# Network Proxy

::: tip Version note
Network proxy is available from 1.11.0.
:::

"Settings → Runtime Environment → Network Proxy" configures a global proxy so all of the app's network requests (AI chat, MCP, model lists, update checks, image downloads, web search) as well as commands inside the container (`curl`, `git`, `npm`, `pip`, etc.) uniformly go through the proxy.

## Proxy Settings

- **Enable proxy**: the master switch; turning it off restores direct connections for all requests.
- **Type**: tap to select HTTP or SOCKS5 from the bottom sheet.
  - **HTTP**: forwarded through a CONNECT tunnel, can proxy all http/https traffic. A local Clash at `127.0.0.1:7890` is this type.
  - **SOCKS5**: TCP-level forwarding (Clash usually uses port `7891`). Note that npm and pip inside the container have limited SOCKS5 support.
- **Server address / port**: the proxy address.
- **Authentication** (optional): fill in when the proxy needs a username and password. Local proxies without authentication (such as Clash) don't need configuration.
- **Addresses that bypass the proxy**: comma-separated; matches connect directly. Supports hostnames, the wildcard `*`, subdomains `*.example.com`, `host:port`, and CIDR ranges. The defaults already include localhost and common intranet ranges.

After filling everything in, a **connectivity test** card appears below:

- **Test address**: defaults to `https://www.google.com`, can be changed to any target.
- **Test proxy**: sends a request to that address through the current proxy to confirm the proxy really works. The test doesn't display the exit IP, nor does it send your real IP to third-party services.

## Per-Provider Proxy

An individual provider can also have its own dedicated proxy. In "AI Providers → Edit page → Options → Network Proxy", the UI is basically the same as the global proxy page (minus "Addresses that bypass the proxy").

When enabled, this provider's AI requests go through the dedicated proxy while other requests still use the global proxy; when disabled, it follows the global one. Changes take effect together with saving the provider edit page.

The matching rule matches exactly on the provider Base URL's **host plus path prefix**, so providers on the same host with different paths can each have a different proxy, and requests are dispatched by the most specific path prefix. Only when multiple providers have exactly the same host and path (same endpoint, different keys) can they not be distinguished — in that case, the one configured first takes effect.

Suitable for situations where multiple providers share the same gateway domain but with different paths, each needing a different exit.

::: tip Commands inside the container are not affected by per-provider proxies
Commands inside the container always use the global proxy.
:::

## When It Takes Effect

All changes **take effect immediately** — new connections go through the proxy right away, no app restart needed.

However, resident processes already started inside the container won't inherit the new environment variables; you need to restart that process or the whole container. Inside the container, the proxy is implemented through environment variables: the HTTP type injects `HTTP_PROXY` / `HTTPS_PROXY` / `ALL_PROXY` / `NO_PROXY`; the SOCKS5 type injects `ALL_PROXY=socks5h://...` (using remote DNS).

## Notes

- Proxy configuration is stored in the app's private directory and doesn't affect system-level proxy settings.
- When the proxy is unavailable (wrong address, proxy not started), network requests will fail; turn off "Enable proxy" to restore direct connections.
- **In remote SSH mode, commands on the server don't go through the local proxy**; configure it on the server itself.
- HTTPS proxies (where the connection to the proxy server itself uses TLS) are not supported.
