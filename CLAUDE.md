# EVE Missions Bot

EVE Online mission automation. Java drives bot logic + window management; Python does OpenCV template matching and OCR. They talk over a long-lived stdin/stdout JSON daemon.

# Personal preferences
## Output style
- **Be concise.** Explain in a few lines max. Skip the trailing wind-downs — the "one flag worth noting…", "a couple things to keep in mind…", option-survey closers. State what was done or found, then stop.
- Still call out the genuinely important: risky/irreversible actions, real bugs, and decisions that need my input. Trim filler, keep substance.
- **One line, always.** Every user-visible message is a single line — the final answer AND any text before/around tool calls. No preamble ("Let me…", "The cause is…"), no mid-work narration, no reasoning, no reporting, no breakdowns. Do the work silently via tools, then print one line stating what was done or found. If I truly need to explain something complex or ask a question, that too is as short as possible — but default to one line.

## Build & Run
- **Build:** `mvn package` → `target/eve-multi-client-bot-1.0-SNAPSHOT-jar-with-dependencies.jar` (main class `evebot.Main`, Java 11)
- **Python OCR:** `pip install -r ocr/requirements.txt`, runs as `python ocr/eve_automation.py --daemon`
- **Controls:** `Ctrl+Shift+O` = start/pause, `Ctrl+Shift+R` = reset state. Bot stays idle until toggled.
- **Deps:** Gson, slf4j-simple, JNA + jna-platform

## Architecture
- `src/main/java/evebot/` — bot logic, state machine, window management (JNA)
- `ocr/` — OpenCV template matching + pytesseract OCR
- `OCRBridge.java` ↔ long-lived `eve_automation.py --daemon` over stdin/stdout JSON (one persistent process, not per-call spawn)
- Poll loop: `monitorClients()` every 500ms; only the **foreground** EVE client is processed each tick

## Config & Data
- `config/mission_rules.json` — accept/decline rules
- `config/faction_decline.json` — faction declines
- `ocr/config/ship_mission_map.json` — OCR keyword → ship template mapping
- `templates/*.png` — OpenCV match targets; missing templates are skipped silently (no crash)

## Conventions
- **Never press Escape in EVE** — dismiss context menus with a left-click in empty space.
- **On "commit and push":** commit straight to master and push. No branch, no PR.
- **Don't add features, commands, or code beyond what's explicitly requested.** Ask when unsure rather than guessing.
