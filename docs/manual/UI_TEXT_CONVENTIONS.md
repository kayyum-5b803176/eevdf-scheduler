# UI text conventions

The rule this whole setup exists to enforce:

> **All UI text is lowercase, except abbreviations and acronyms — those stay uppercase.**

Everything below is in service of that one line.

---

## The rule

Every string visible to the user — tab labels, section headers, row titles,
row subtitles, button labels, placeholder text — follows the same casing rule:

```
all words lowercase
     ↕
abbreviations and acronyms stay uppercase
```

This applies regardless of where the string lives: `android:text`, `setText()`,
`hint`, `contentDescription`, dialog messages, Snackbars.

---

## Examples

| String | Correct | Wrong |
|---|---|---|
| UI customization | `UI customization` | `ui customization` / `UI Customization` |
| sound and vibration | `sound and vibration` | `Sound and Vibration` / `SOUND AND VIBRATION` |
| data and backup | `data and backup` | `Data and Backup` |
| button action config | `button action config` | `Button Action Config` |
| EEVDF scheduler | `EEVDF scheduler` | `eevdf scheduler` / `Eevdf Scheduler` |
| API key | `API key` | `Api key` / `api key` |
| reserved — modify level critical | `reserved — modify level critical` | `Reserved — Modify Level Critical` |

---

## Known abbreviations that stay uppercase

These are always written in uppercase regardless of position in the string:

```
UI      — user interface
UX      — user experience
API     — application programming interface
ID      — identifier
EEVDF   — earliest eligible virtual deadline first
DL      — deadline
RT      — real-time
SI      — international system of units
```

Add to this list when a new abbreviation is introduced.

---

## What does not change

- XML attribute names (`android:text`, `app:tabMode`) — these are code, not UI text
- Package names, class names, variable names — follow language conventions (camelCase, PascalCase)
- Color resource names, string resource keys — follow the project's existing naming conventions
- Code comments — write normally

---

## Where this applies in settings

The settings page (`activity_settings.xml`) uses this convention throughout:

- **Tab labels**: `platform`, `app`, `core`, `data` — all lowercase (no abbreviations)
- **Section headers**: `visual`, `sound and vibration`, `button action`, etc. — all lowercase
- **Row titles**: `UI customization`, `auto switch`, `multiuser sync` — lowercase except `UI`
- **Row subtitles**: `appearance, layout, density, overlay` — all lowercase
- **Reserved placeholders**: `no settings available yet`, `reserved for log viewing` — all lowercase

---

## Checklist before adding a title or description

- [ ] All words lowercase
- [ ] Checked the abbreviations list — any acronyms kept uppercase
- [ ] No title case (not `Sound And Vibration`, not `Button Action Config`)
- [ ] No sentence case beyond the first word (not `Sound and vibration` with a capital S)
