# Default Models

In "Settings → Default Models", besides the default model used for new sessions, you can also assign models separately for a few specific purposes.

## Image Recognition Model

Determines which model the AI uses to look at images.

- **Follow current chat model** (default): uses the chat model you currently have selected to view images.
- **Specify a dedicated model**: always uses a particular model for images, not following chat model switches. Suitable when your chat model doesn't support images, or when you want to use a cheaper model to handle images.

Each model in the list is marked with whether it supports image input, for your reference. Note that there is **no hard blocking** here: if you pick a model that doesn't support images, image recognition will fail, and the AI receives the error and handles it itself (usually by suggesting you switch models).

Two things worth knowing about image recognition usage:

- The AI can give the image recognition model up to 4 to 5 images at once for comparative analysis, and can keep asking follow-ups within the same image recognition session afterwards; the model retains the previously viewed images and Q&A.
- Images are compressed on demand by default to save tokens; you can also request the original image to be sent directly, or a lower-resolution mode that saves more tokens.

## Image Generation Model

Sets the model used by the `generateImage` tool, supporting OpenAI and Gemini compatible APIs.

- The OpenAI compatible API supports parameters such as size, count, quality, background, style, and output format.
- The Gemini compatible API uses the model's default size and resolution; generating multiple images calls the API multiple times.
- Images are saved by default to `~/.aicode/generated-images/`, or you can specify a workspace path. A single image is limited to 20MB, and a single call to 48MB in total.

## Image Generation via Image-Capable Chat Models

After connecting a chat model with image output support through the Gemini compatible API, you can generate images directly, upload reference images, and keep modifying the generated results in later conversation.

## Title Summary Model

After you send the first message in a new session, AiCode automatically generates a title for the session. You can specify a dedicated model for this task here.

- **Follow current chat model** (default).
- **Specify a dedicated model**: a cheap, fast small model is recommended — a job like titles doesn't need a good model.

Title generation runs asynchronously in the background and won't slow down your conversation. If generation fails or returns nothing, the temporary title the system truncated from the first sentence is kept, which doesn't affect usage.

The prompt used to generate titles can also be customized — see "Custom Prompts".
