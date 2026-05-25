
# Cumulus 2.0 — API Snapshot

**Source:** geysermc/cumulus (context7)
**Pinned version:** `org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT`
**Snapshot date:** 2026-05-24

## Key Classes / Interfaces

| Class | Package | Role |
|---|---|---|
| `SimpleForm` | `org.geysermc.cumulus.SimpleForm` | Simple button form (builder available) |
| `ModalForm` | `org.geysermc.cumulus.ModalForm` | Yes/no form (builder available) |
| `CustomForm` | `org.geysermc.cumulus.CustomForm` | Full custom form (builder available) |
| `Form` | `org.geysermc.cumulus.Form` | Base form interface |
| `FormResponse` | `org.geysermc.cumulus.response.FormResponse` | Base response |
| `SimpleFormResponse` | `org.geysermc.cumulus.response.SimpleFormResponse` | Button click response |

## Critical Signatures — Form Builders

### SimpleForm

```java
SimpleForm.builder()
    .title(String)
    .content(String)
    .button(String)                          // sequential id: 0, 1, 2...
    .optionalButton(String, boolean)         // present if true, always id=0
    .validResponseHandler(response -> {
        int clickedId = response.clickedButtonId();
    })
    .closedOrInvalidHandler(() -> { /* ... */ })
    .build();
```

### ModalForm

```java
ModalForm.builder()
    .title(String)
    .content(String)
    .button1(String)                         // id = 0 (true/yes)
    .button2(String)                         // id = 1 (false/no)
    .build();
// response.getResult() == true if button1 was clicked
```

### CustomForm

```java
CustomForm.builder()
    .title(String)
    .label(String)
    .input(String label, String placeholder, String defaultText)
    .dropdown(String text, String... options)
    .toggle(String text, boolean defaultState)
    .slider(String text, float min, float max, float step, float defaultValue)
    .validResultHandler(response -> {
        // response.next() reads components in order: String, Integer, Boolean, Float
    })
    .build();
```

## Sending Forms to Bedrock Players

```java
// Cumulus forms are sent via FloodgateApi
FloodgateApi floodgateApi = FloodgateApi.getInstance();
if (floodgateApi.isFloodgatePlayer(uuid)) {
    Form form = SimpleForm.builder().title("...").button("OK").build();
    floodgateApi.getPlayer(uuid).sendForm(form);
}
```

## Form Response Handling

```java
form.setResponseHandler((formObj, responseData) -> {
    FormResponse response = formObj.parseResponse(responseData);
    if (!response.isCorrect()) {
        // closed or malformed
        return;
    }
    // process valid response
});
```

## EnthusiaMarket Usage Notes

- Forms shown when `bedrock.force-forms: true` OR when sender is a Floodgate player (REQ-011).
- `bedrock.form-timeout-sec: 60` controls form expiry (not a Cumulus API value; project-level).

## Breaking-Change Watchpoints

1. `response.next()` on CustomForm reads values **in component declaration order** — do not reorder components in existing forms without updating handler logic.
2. `FloodgateApi.getPlayer(UUID)` — returns null for online Java players; always null-check.
3. Form builders are immutable after `.build()` — recreate to modify.

## Evidence

- context7:/geysermc/cumulus — SimpleForm, ModalForm, CustomForm builders, response handling, Floodgate integration
- org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT (pinned in build.gradle.kts:27)
