THERAGENX – Groq completion truncation fix

Root cause found in the reviewed source:
- GroqProvider did not send max_completion_tokens.
- Groq's reasoning-model documentation lists 1024 as the default completion cap.
- The gpt-oss-120b request therefore inherited a 1024-token output ceiling.
- The internal completionReserveTokens value only affects our application's local
  rate-limiter reservation; it does NOT set the API max_completion_tokens parameter.

Changes:
- GroqProvider.java now explicitly sends max_completion_tokens (default 4096).
- The value is configurable with app.groq.max-completion-tokens.
- Request logs show the completion cap actually sent.
- A finish_reason=length response is rejected explicitly rather than being treated
  as a complete extraction.

Configuration (optional; the default works without adding this):
app.groq.max-completion-tokens=4096

Intentionally unchanged:
- No reasoning_effort change; the model's existing/default reasoning behaviour is preserved.
- No document chunking or request input-budget changes.
- No GroqTokenRateLimiter changes.
- No JSON response schema changes.
- No prompt, evidence assembly, case grouping, validation, or Java-side evidence-offset changes.

Why 4096:
- Groq documents a 1024-token default for reasoning models, which is too small for
  the full catalog JSON response in D04.
- Groq lists 65,536 as the maximum completion tokens for openai/gpt-oss-120b.
- 4096 gives the structured response room to finish without setting the per-request
  output ceiling unnecessarily high. It is an output ceiling, not an input TPM setting.

Replacement:
Replace the extraction folder with the extraction/ folder in this ZIP, or replace only:
extraction/provider/GroqProvider.java

Verification:
The ZIP was checked for integrity and the edited source was inspected.
A full Maven build and live D04/D08 regression run were not possible because the
uploaded archive does not contain the complete Spring Boot project or API credentials.
