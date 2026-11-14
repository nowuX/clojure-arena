# Design system

## Visual rule

Use a monochrome interface. Hue is reserved for player bodies and the compact
identity swatches beside their IDs. Everything else uses neutral grayscale:
page, canvas background, solid/semisolid platforms, cards, borders, controls,
focus rings, selection outlines, text, results, protection, and connection states.

Do not use purple or blue-tinted surfaces, colored gradients, decorative colored
accents, or semantic red/green/yellow status backgrounds. Platform type is shown
with solid fill versus outline, not different hues. Player colors do not become
card fills, selection borders, body shadows, or control accents.

## Neutral palette

| Token | Value | Role |
| --- | --- | --- |
| `background` | `#101010` | Page and arena background |
| `surface` | `#181818` | Panels and controls |
| `surface-raised` | `#242424` | Player rows and result entries |
| `border` | `#484848` | Panel edges and dividers |
| `muted` | `#b3b3b3` | Secondary text |
| `text` | `#f2f2f2` | Primary labels and controlled-player marker |
| `platform` | `#9c9c9c` | Solid fill and semisolid outlines |

All neutral hex values have equal red, green, and blue channels. Keep the first
page plain; introduce additional spacing, cards, and responsive refinements only
when their checkpoint needs them. Use a system font, visible focus indicators,
readable contrast, and short Spanish labels.

## Player identity

Use four distinct body colors, allocated by the server once networking exists:

| Identity | Body / swatch color |
| --- | --- |
| `:cyan` | `#22d3ee` |
| `:crimson` | `#e11d48` |
| `:amber` | `#f59e0b` |
| `:green` | `#4ade80` |

Use IDs and text alongside swatches; color alone must never carry identity.
Facing dots, the `Tú` label, protection rings, and the controlled row's border
remain white/gray, including when the player has a colored body. A waiting or
inactive player keeps its identity row without an arena body.

## Responsive presentation

The simulation and Canvas backing size stay 800 × 480. CSS scales the canvas proportionally to available width. Wrap
long IDs and server addresses. Player rows and result standings wrap without
horizontal page overflow. Show A/D, W, S, and primary-click controls visibly.
No touch controls or new input mechanics are implied by small-screen support.
