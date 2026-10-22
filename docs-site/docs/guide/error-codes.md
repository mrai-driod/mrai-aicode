# Common Error Messages

When an AI request fails or the network fluctuates, the chat page first shows a "Retrying N/6" bubble, whose first line states the reason for this retry; when retries are exhausted and it still fails, the error is shown in a red bubble. This article explains what these messages mean by category and how to troubleshoot them.

## Retry Message Categories

When a request disconnects mid-way or the server has a temporary anomaly, AiCode automatically retries (up to 6 times, with exponential backoff). Before each retry, the bubble first shows the failure reason:

| Bubble message | Trigger condition | Possible causes & troubleshooting |
| --- | --- | --- |
| Rate limited (429) | Server returned 429 | Requests too frequent, or your plan quota is nearly exhausted. Just wait for the automatic retry; if it happens frequently, check usage in the provider's console |
| Server overloaded (503) | Server returned 503 | The model service or relay station is overloaded — a temporary fault; just wait for the automatic retry |
| Server error (5xx) | Server returned 500 / 502 / 504, etc. | A server-side anomaly or gateway problem, unrelated to your configuration; retry later |
| Connection timed out | Establishing a connection or waiting for a response timed out | Slow network, no response from the service, or a slow relay station. Check your network; change network or node if necessary |
| Connection refused | No service listening on the target port | The provider Base URL is wrong (domain / IP or port incorrect), or the service isn't started; verify in "Settings → AI Providers" |
| DNS resolution failed | Domain name resolution failed | Network not connected, or the domain of a custom Base URL is misspelled |
| Connection interrupted | Connection dropped after being established, stream interrupted | Network jitter, unstable proxy, or the server actively disconnecting. Just retry automatically; if it recurs, check network and proxy settings |
| SSL handshake failed | TLS certificate verification failed | With a custom Base URL, confirm the protocol (https) and certificate are correct, or the server certificate is abnormal |
| Network connection lost | Other network-layer faults | Mobile network switching, weak network environment; check your network and retry manually |
| Network error | Unclassifiable error | Read the specific error text; if still unresolved, attach logs when sending feedback |

## Common HTTP Error Codes

When a request fails, the retry bubble and the final error bubble include the HTTP status code. Common status codes mean the following:

| Status code | Meaning | Possible cause |
| --- | --- | --- |
| 401 | Authentication failed | API key wrong, expired, or revoked; check the key in "Settings → AI Providers" |
| 403 | Insufficient permission | The key has no permission for that model or endpoint, regional restrictions, or zero balance |
| 404 | Endpoint not found | Custom Base URL path is wrong (e.g. missing `/v1`); verify in provider settings |
| 429 | Rate limited | Requests too frequent or quota exhausted; retry later or upgrade your plan |
| 500 | Internal server error | Temporary server-side fault, unrelated to configuration |
| 502 | Gateway error | The relay station's upstream is unavailable |
| 503 | Service overloaded | Server load too high; retry later |
| 504 | Gateway timeout | Upstream response timed out |

## Relation to Multi-Key Switching

With multi-key enabled in "Settings → AI Providers", only errors where **the key itself is the problem** (authentication failed, insufficient permission, rate limited, balance exhausted) consume the failure count and trigger a key switch; errors like server 5xx, request timeouts, and unreachable network can't be fixed by switching keys, so they don't trigger a mistaken switch. When multi-key is not enabled, rate limiting (429) has no key to switch to and retries with backoff like other transient faults. See [AI Providers & Models → Multi-Key](/guide/providers).
