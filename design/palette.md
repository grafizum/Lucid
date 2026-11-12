# Lucid — Parchment & Charcoal

A warm-neutral system in three families — **cream** (paper), **beige** (raised warm
surface), **charcoal** (ink and dark canvas) — with one restrained accent, **clay**,
and three semantic tones. No pure white, no pure black, no cool greys: every step has
a little warmth in it, which is what keeps the app from looking like a default
Material screen.

The contrast rule: **cream never sits on cream, ink never sits on ink.** Every text
style is one family away from its surface.

---

## 1. Cream — paper, light surfaces

| Token       | Hex       | Use                                              |
| ----------- | --------- | ------------------------------------------------ |
| `cream_50`  | `#FCF8F1` | card cores, bubbles, the brightest paper          |
| `cream_100` | `#F8F2E7` | **app canvas** (the default background)           |
| `cream_200` | `#F1E9DA` | recessed rows, chips, inactive track              |
| `cream_300` | `#E7DCC7` | insets, pressed surface, ghost button fill        |

## 2. Beige — the warm middle

| Token       | Hex       | Use                                              |
| ----------- | --------- | ------------------------------------------------ |
| `beige_200` | `#EBE1CE` | eyebrow pills, quiet buttons                      |
| `beige_300` | `#DFD2B8` | **outer shell** of the double-bezel cards         |
| `beige_400` | `#CDBB9B` | hairlines and dividers on cream                   |
| `beige_500` | `#B39D7C` | icon strokes, the lens selection wash base        |

## 3. Charcoal — ink and dark canvas

| Token       | Hex       | Use                                              |
| ----------- | --------- | ------------------------------------------------ |
| `ink_900`   | `#191814` | **primary text on cream**, dark canvas            |
| `ink_800`   | `#23221E` | **dark raised surface** (translation card)        |
| `ink_700`   | `#2E2C27` | dark shell, borders on dark                       |
| `ink_600`   | `#3A3831` | dark inset, disabled ink                          |

## 4. Stone & sand — the muted middle of each mode

Readable, never grey-blue.

| Token       | Hex       | Use                                              |
| ----------- | --------- | ------------------------------------------------ |
| `stone_400` | `#A69C8C` | decorative strokes on cream                       |
| `stone_500` | `#857B6C` | micro-labels on cream (uppercase only)            |
| `stone_600` | `#6A6254` | **secondary text on cream** — 5.4:1               |
| `sand_400`  | `#C2B9A6` | secondary text on ink                             |
| `sand_500`  | `#A9A08E` | meta text on ink — 6.1:1                          |
| `sand_300`  | `#D8CFBC` | body text on ink when cream is too loud           |

## 5. Clay — the single accent

Used for exactly three things: the eyebrow, the live status dot, and the read-text
wash. Never for large fills.

| Token       | Hex       | Use                                              |
| ----------- | --------- | ------------------------------------------------ |
| `clay_300`  | `#D3A582` | accent text **on dark** — 7.2:1                   |
| `clay_400`  | `#B9825F` | accent graphics on dark                           |
| `clay_500`  | `#A46E4B` | accent graphics on cream, icon 文                  |
| `clay_600`  | `#8A5A3C` | **accent text on cream** — 5.2:1                  |
| `clay_700`  | `#6E4630` | pressed accent                                    |

## 6. Semantics

| Token       | Hex       | Meaning                                    |
| ----------- | --------- | ------------------------------------------ |
| `olive_600` | `#55603F` | done / on / allowed                        |
| `amber_600` | `#8A6A2B` | nothing found, needs attention             |
| `rose_600`  | `#8C3B2F` | trouble, permission denied                 |

## 7. Translucency tokens

| Token            | Value                            | Use                            |
| ---------------- | -------------------------------- | ------------------------------ |
| `hairline_light` | `rgba(25,24,20,0.08)`            | dividers on cream              |
| `hairline_dark`  | `rgba(252,248,241,0.10)`         | dividers on ink                |
| `wash`           | `rgba(179,157,124,0.55)`         | the selection over read text   |
| `scrim`          | `rgba(20,18,15,0.55)`            | the language sheet dim         |
| `shell_light`    | `rgba(205,187,155,0.45)`         | outer shell of light cards     |
| `shell_dark`     | `rgba(46,44,39,0.85)`            | outer shell of dark cards      |

---

## Contrast, measured

| Pair                                  | Ratio    | Verdict              |
| ------------------------------------- | -------- | -------------------- |
| `ink_900` on `cream_100`              | 15.9 : 1 | body + display       |
| `ink_900` on `cream_50`               | 16.8 : 1 | card text            |
| `stone_600` on `cream_100`            | 5.4 : 1  | secondary text       |
| `clay_600` on `cream_100`             | 5.2 : 1  | accent text          |
| `cream_50` on `ink_800`               | 15.0 : 1 | card text on dark    |
| `sand_300` on `ink_800`               | 10.3 : 1 | body on dark         |
| `sand_500` on `ink_800`               | 6.2 : 1  | meta on dark         |
| `clay_300` on `ink_800`               | 7.2 : 1  | accent text on dark  |
| `olive_600` on `cream_100`            | 6.0 : 1  | done / on state      |
| `amber_600` on `cream_100`            | 4.5 : 1  | warning (large only) |
| `rose_600` on `cream_100`             | 6.8 : 1  | error state          |

## Android resource mapping

```
cream_50 cream_100 cream_200 cream_300
beige_200 beige_300 beige_400 beige_500
stone_400 stone_500 stone_600
ink_900 ink_800 ink_700 ink_600
sand_300 sand_400 sand_500
clay_300 clay_400 clay_500 clay_600 clay_700
olive_600 amber_600 rose_600
```

Replacing today's `ink / ink_raised / white / ink_2 / ink_3 / mint / amber / rose /
hairline` — the old mint (`#7BF1C6`) is retired: on parchment it reads as a screen
colour, not a paper colour.
