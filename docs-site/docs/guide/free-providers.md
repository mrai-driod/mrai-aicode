# Free Providers

Mrai AiCode works out of the box — two free providers are built in and need no registration and no API key. This page explains them first, then lists other free platforms you can connect yourself with your own key.

## Built in: Mrai AI (default)

- **Cost**: free, no signup, no key.
- **How it works**: Mrai AI is a fallback chain, ported from Mrai Buddy. Each request tries, in order:
  1. **Pollinations** (OpenAI-compatible) — the full agent path; tool calls work here.
  2. **PowerBrain** — a second, independent service (text replies).
  3. **Pollinations plain-text GET** — a last-resort path on a different pool.
- A step counts as failed if it errors or answers empty; the chain then moves on silently. You never see which step answered, and advertising blocks injected by free services are stripped from replies automatically.
- **Good to know**: free services can be slow or briefly saturated at busy times. If a reply fails, just send the message again — the chain re-tries from the top.

## Built in: OpenCode Free

- **Cost**: free, no signup, no key.
- **What it is**: the free model catalog of [OpenCode](https://opencode.ai) (Zen) — models such as Big Pickle and the other `-free` models.
- **Switching**: on the home screen, open the model picker and choose the **OpenCode Free** provider and the model you want. Different free models have different strengths; Big Pickle is the default.

## Other free platforms (bring your own key)

These platforms offer free quotas or promotional tokens after registration. Free quotas usually expire or need topping up; each platform's own rules apply.

## MiMo Open Platform (Xiaomi)

- **Platform**: Xiaomi MiMo Open Platform
- **Registration**: [platform.xiaomimimo.com](https://platform.xiaomimimo.com)
- **Description**: Xiaomi's self-developed MiMo series models; after registering you can apply for an API key to connect.

## SenseNova

- **Platform**: SenseNova large model open platform (SenseTime)
- **Registration**: [platform.sensenova.cn](https://platform.sensenova.cn/login)
- **Description**: The large model platform under SenseTime; after registering and logging in you can apply for an API key.

## TokenRhythm (OpenSquilla)

- **Platform**: TokenRhythm
- **Official website**: [tokenrhythm.studio](https://tokenrhythm.studio/)
- **Description**: A unified multi-model API access platform, paired with OpenSquilla intelligent routing; after registering you can apply for an API key to connect.

## SiliconFlow

- **Platform**: SiliconFlow open platform
- **Website**: [cloud.siliconflow.cn](https://cloud.siliconflow.cn)
- **Description**: An inference platform with periodic free-quota campaigns for new users — refer to platform announcements for current details.

## Agnes AI

- **Platform**: Agnes AI (Sapiens AI team, Singapore)
- **Registration**: [platform.agnes-ai.com](https://platform.agnes-ai.com/)
- **Description**: An all-modality AI platform — text, image, and video model APIs, with an OpenAI compatible API; after registering you can apply for an API key to connect.

## Connecting your own provider

Once you have a platform's API key and Base URL, configure it per the steps in [AI Providers & Models](/guide/providers):

1. Open "Settings → AI Providers" and tap + at the top right to create new.
2. Fill in the upstream format, API key, and Base URL (don't include `/v1` in the address).
3. Switch to the "Models" tab to fetch models, then return to the home page to start chatting.

If a platform has special requirements for the upstream format or Base URL (e.g. offering an OpenAI compatible or Anthropic compatible endpoint), follow the platform's documentation.
