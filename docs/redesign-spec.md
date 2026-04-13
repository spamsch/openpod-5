# OpenPod Redesign Spec

Status: Draft v1
Audience: Product, design, and Android engineering

## 1. Scope

This document defines the next visual system for OpenPod across dashboard, history, settings, onboarding, pairing, and future treatment flows.

User request used the term `Material 5`. Google’s currently published system is still `Material 3`, with newer `Material 3 Expressive` guidance. This spec interprets `Material 5` as:

- A forward-looking OpenPod design system built on current Material guidance
- More premium, calmer, and more intentional than stock Android defaults
- Closer to the polish level expected by iOS users without becoming an iOS clone

## 2. Product Intent

OpenPod should feel:

- Clinically trustworthy
- Calm under stress
- Premium, not flashy
- Glanceable in under 2 seconds
- Consistent across all flows

OpenPod should not feel:

- Generic Material demo UI
- Dense and settings-heavy
- Over-colored
- Dependent on floating controls that look detached from the layout

## 3. Core Principles

### 3.1 One Hero Per Screen

Each screen gets one dominant focal point:

- Dashboard: current glucose and delivery state
- History: timeline and filters
- Settings: grouped account/device/system areas
- Pairing/onboarding: current task and next action

Do not place multiple competing “primary” cards on the same viewport.

### 3.2 Safety First, Then Beauty

Visual polish must improve comprehension, not decorate over it.

- Status colors are semantic, not decorative
- Glucose colors are reserved for glucose meaning
- Error red is reserved for true risk, failure, or destructive actions
- Primary brand color is never used to mimic a glucose state

### 3.3 Calm Surface Hierarchy

The app should use fewer, more intentional layers:

- Background
- Primary content surface
- Elevated action surface
- Alert surface

Avoid stacking multiple outlined gray cards inside other gray containers.

### 3.4 Expressive, Not Novelty

Use rounded shapes, generous spacing, and motion to feel premium. Do not introduce playful visual effects that weaken clinical confidence.

## 4. App-Wide Foundations

### 4.1 Layout Grid

- Base spacing unit: `4dp`
- Standard spacing scale: `4, 8, 12, 16, 20, 24, 32, 40, 48`
- Mobile content gutter: `20dp`
- Max readable content width for large text blocks: `560dp`
- Minimum touch target: `48dp`
- Preferred touch target for primary actions: `56dp`

### 4.2 Shape System

Use one rounded system across the app.

- `shape.xs = 12dp`
- `shape.sm = 16dp`
- `shape.md = 20dp`
- `shape.lg = 28dp`
- `shape.xl = 32dp`
- `shape.full = 999dp`

Usage:

- Chips and compact pills: `full`
- Buttons, small cards, list cells: `16dp`
- Hero cards and trays: `20dp`
- Modal sheets and major task containers: `28dp`

### 4.3 Elevation Model

Prefer tonal separation over heavy shadows.

- Base background: flat
- Resting card: tonal container, no visible shadow
- Important tray/FAB/sheet: low shadow plus tonal contrast
- Modal surfaces: strongest separation

Use shadow only when the component is floating or moving.

### 4.4 Edge-to-Edge

All top-level screens should render edge-to-edge.

- Transparent system bars
- Content padded using window insets
- Navigation chrome treated as part of the composition, not as a hard slab

## 5. Color System

### 5.1 Strategy

Use a stable branded palette, not unrestricted dynamic color. Clinical semantics matter more than wallpaper personalization.

Dynamic color may be offered as an optional accent harmonization mode, but:

- Never remap glucose/status semantics
- Never reduce contrast on critical data
- Never recolor alerts into ambiguous tones

### 5.2 Brand Direction

Brand should feel precise, modern, and calm.

- Primary hue family: mineral blue / deep cobalt
- Supporting accent: cool teal for non-critical informational accents
- Neutrals: warm off-white in light theme, charcoal mineral neutrals in dark theme

Avoid purple as the default brand accent.

### 5.3 Recommended Tokens

Light theme:

- `primary = #3257D6`
- `onPrimary = #FFFFFF`
- `primaryContainer = #E1E8FF`
- `onPrimaryContainer = #0D1B52`
- `secondary = #0E6E6A`
- `background = #FBFAF7`
- `surface = #FBFAF7`
- `surfaceContainerLow = #F4F2EE`
- `surfaceContainer = #EEEBE5`
- `surfaceContainerHigh = #E7E3DC`
- `onSurface = #1A1C1E`
- `onSurfaceVariant = #5E646B`
- `outlineVariant = #D4D8DD`
- `error = #B3261E`
- `errorContainer = #F9DEDC`

Dark theme:

- `primary = #BAC7FF`
- `onPrimary = #0A1650`
- `primaryContainer = #1F3C9D`
- `onPrimaryContainer = #E1E8FF`
- `secondary = #7FD0C8`
- `background = #111315`
- `surface = #111315`
- `surfaceContainerLow = #171A1D`
- `surfaceContainer = #1C1F23`
- `surfaceContainerHigh = #252A2F`
- `onSurface = #E3E7EB`
- `onSurfaceVariant = #AAB1B8`
- `outlineVariant = #3A4046`
- `error = #FFB4AB`
- `errorContainer = #601410`

### 5.4 Semantic Tokens

These are not brand colors and must remain stable.

- `glucose.inRange = #1FA971`
- `glucose.high = #BD8400`
- `glucose.low = #D64B52`
- `glucose.urgentLow = #B3261E`
- `insulin.active = #2B8CFF`
- `insulin.basal = #5B6FD8`
- `pod.connected = #1FA971`
- `pod.warning = #C88719`
- `pod.error = #D64B52`

Rule: semantic colors appear only where the meaning is clinical or stateful.

## 6. Typography

### 6.1 Type Direction

Typography should feel cleaner and more premium than default Android while staying within Material scale logic.

Recommended path:

- Use `Roboto Flex` or `Google Sans` if asset/legal path is approved
- Otherwise keep platform sans, but tighten the scale and weight mapping

Numbers must use tabular figures in every glucose, insulin, time, and dosage context.

### 6.2 Type Scale

- Hero glucose: `72/76`, medium, tabular
- Display large: `56/60`, medium
- Display medium: `44/52`, medium
- Headline large: `30/36`, medium
- Headline medium: `24/30`, medium
- Title large: `18/24`, medium
- Title medium: `16/22`, medium
- Body large: `16/24`, regular
- Body medium: `14/20`, regular
- Label large: `14/20`, medium
- Label medium: `12/16`, medium
- Label small: `11/14`, medium

Rules:

- Screen titles use `headlineLarge`
- Section labels use `labelLarge` or `titleSmall`, never oversized gray headings
- Metadata uses `labelMedium` or `bodySmall`
- Hero metrics always use tabular numerals

## 7. Navigation and Screen Chrome

### 7.1 Top-Level Screens

Top-level destinations use:

- Large or medium top app bar
- Bottom navigation bar
- Optional floating action only if truly global

Top-level screens in OpenPod:

- Dashboard
- History
- Settings

### 7.2 Bottom Navigation

Bottom navigation should feel lighter and more integrated than it does now.

- Use Material navigation bar with an expressive active indicator
- Increase horizontal breathing room
- Use icon + label for all three tabs
- Active state uses tonal indicator, not a saturated purple blob
- Bar surface should be slightly elevated from the page, especially in light theme

Recommended sizing:

- Navigation bar height: `80dp`
- Active indicator corner radius: `full`
- Icon size: `24dp`

### 7.3 Primary Actions

Do not default to a detached circular FAB.

Preferred priority:

1. Inline primary action in a bottom action tray
2. Extended FAB if the action must persist while scrolling
3. Standard FAB only for low-text, high-recognition utilities

For OpenPod, `Bolus` should be an `ExtendedFloatingActionButton` or bottom action tray, not a small isolated icon button.

## 8. Canonical Screen Templates

### 8.1 Template A: Dashboard / At-a-Glance

Structure:

1. Compact status strip or capsule
2. Hero metric block
3. One row of related secondary metrics
4. Key activity cards
5. Persistent primary treatment action

### 8.2 Template B: Timeline / History

Structure:

1. Top app bar with filters
2. Sticky date groups
3. Event cards or timeline rows
4. Summary chips for insulin, carbs, glucose trend, alerts

### 8.3 Template C: Grouped Settings

Structure:

1. Large title
2. Group headers
3. Inset grouped list cells
4. Inline status text
5. Clear separation for destructive items

### 8.4 Template D: Guided Task Flow

Used for onboarding, pairing, and future treatment setup.

Structure:

1. Top bar with dismiss/back
2. Progress indicator
3. One main task body
4. Persistent bottom action row

Do not mix stepper, multiple cards, and many competing calls to action in one viewport.

## 9. Component Rules

### 9.1 Status Capsule

Replace the loose top text row pattern with a capsule or inline segmented summary.

Contains:

- Connectivity dot
- Pod label
- Reservoir
- Time remaining

Behavior:

- One tap target
- Tonal background in light theme
- Minimal separators

### 9.2 Hero Metric Module

Used on dashboard and wherever the app presents the most important medical number.

Contains:

- Trend treatment
- Primary metric
- Unit
- Freshness
- Optional sparkline
- Related support pill such as IOB

Rules:

- Metric must visually dominate the screen
- Labels should be compact and tightly grouped
- Only one strong semantic color at a time
- Do not surround the hero with a heavy card unless needed for contrast

### 9.3 Action Cards

Current action cards should become more list-like and less blocky.

Spec:

- Card radius: `20dp`
- Internal padding: `16-20dp`
- Title at top
- Primary content line with strong contrast
- Metadata line below
- Chevron only when it truly navigates

Cards should read like concise summaries, not settings rows.

### 9.4 Chips and Pills

Use chips for lightweight status and filter states.

- IOB chip: pill surface with icon + value
- Mode chip: automatic/manual/activity
- Filter chips in history

Use full-pill shapes and tonal fills. Avoid outlined chips unless the screen is already visually dense.

### 9.5 Lists

Use grouped inset lists for history and settings.

Cell anatomy:

- Leading icon or avatar only when it helps scan speed
- Primary label
- Secondary supporting text
- Optional trailing value, chevron, or switch

Each cell should feel tappable without requiring borders between all rows.

### 9.6 Banners

Use banners sparingly for:

- Pod disconnected
- Pairing error
- Safety warnings
- Temporary activity mode

Banners should use:

- Tonal container
- Strong leading icon
- One primary sentence
- Optional action

## 10. Dashboard Redesign

The dashboard becomes the visual standard for the rest of the app.

### 10.1 Layout

Order:

1. Status capsule
2. Hero glucose module
3. Secondary metrics row
4. Treatment summary cards
5. Primary bolus action tray

### 10.2 Hero Glucose Module

Recommended composition:

- Trend label and directional icon grouped above the value
- Glucose value centered, large, tabular
- Unit and freshness directly below, closer together
- A small sparkline behind or below the value if data exists
- IOB shown as a supporting pill, not as a separate full-width slab

### 10.3 Secondary Metrics

Immediately under the hero:

- IOB
- Mode
- Optional active basal rate

These should form one secondary metric row or wrap cluster, not separate competing cards.

### 10.4 Treatment Cards

Cards on dashboard should be:

- `Last Bolus`
- `Basal Delivery`
- `Activity Mode` when relevant

These cards should share one anatomy and one spacing model.

### 10.5 Primary Action

Bolus action should become one of:

- Extended FAB with icon + `Bolus`
- Bottom floating action tray with one primary action and optional secondary quick action

The action should align with the overall composition and sit above navigation with enough inset to feel intentional.

## 11. History Redesign

History should not be a blank placeholder with a title. It should become the app’s review and trust screen.

### 11.1 Structure

- Large title: `History`
- Filter chip row: `All`, `Bolus`, `Basal`, `Alerts`, `Pod`
- Daily grouped timeline
- Summary row at top for selected period

### 11.2 Event Cards

Every event entry should expose:

- Event type
- Time
- Primary value
- Secondary context
- Optional status marker

Examples:

- `Bolus` with units and carb context
- `Auto basal adjustment` with rate change
- `Pod alert` with severity

## 12. Settings Redesign

Settings should follow an inset grouped list model.

Groups:

- Therapy
- Pod
- Glucose
- Notifications
- Security
- About

Rules:

- Large title at top
- Section headers small and subdued
- Rows use grouped cards with shared corners
- Destructive actions isolated at the bottom
- Explanatory copy only where needed

## 13. Onboarding and Pairing Redesign

These flows should feel focused and guided, not like regular pages with buttons attached.

### 13.1 Flow Shell

- Centered or top-aligned task title
- Brief explanation
- One primary content region
- Fixed bottom action row

### 13.2 Stepper

Use a quieter stepper.

- Dot or bar based
- Not visually heavier than the task itself
- Always above content, below top bar

### 13.3 Form Controls

- Filled or outlined text fields with large hit targets
- Sliders paired with explicit values
- Selection cards use tonal fill and strong selected state
- Error text appears inline, not hidden in banners unless global

## 14. Motion

Motion should communicate continuity and confidence.

### 14.1 Principles

- Fast for common state updates
- Slightly softer for navigation transitions
- No bouncy novelty on medical data changes

### 14.2 Recommended Durations

- Tap response: `90-120ms`
- Card expand/collapse: `180-220ms`
- Screen enter/exit: `240-300ms`
- Hero number/state changes: crossfade or content transform `160-200ms`

### 14.3 Motion Patterns

- Use shared axis for flow steps
- Use fade-through for tab changes and dashboard state swaps
- Use subtle scale and elevation for action trays/FAB reveal

## 15. Accessibility and Safety

- Support font scaling to at least `200%` without clipping essential actions
- Maintain `4.5:1` contrast for body text and `3:1` for large text/components at minimum
- Do not communicate glucose state by color alone
- Provide semantic labels for dosage, time, connection, and warnings
- Preserve stable spatial positions for critical actions
- Avoid animation on urgent alerts beyond a single attention pulse

## 16. Implementation Mapping

This spec can be implemented on the current codebase without a full rewrite.

### 16.1 Theme Layer

Update:

- `core/ui/src/main/kotlin/com/openpod/core/ui/theme/Color.kt`
- `core/ui/src/main/kotlin/com/openpod/core/ui/theme/Theme.kt`
- `core/ui/src/main/kotlin/com/openpod/core/ui/theme/Type.kt`

Actions:

- Add complete light and dark tonal palettes
- Add semantic extension tokens for glucose, insulin, pod, warning, success
- Rebalance typography for hero metrics and titles
- Make edge-to-edge defaults part of app theme setup

### 16.2 Shared UI Components

Refactor or replace:

- `PodStatusBar.kt`
- `GlucoseDisplay.kt`
- `IobChip.kt`
- `ActionCard.kt`
- `WizardStepper.kt`

Actions:

- Convert pod status into a capsule summary
- Merge hero metric, sparkline, and secondary pills into a reusable dashboard hero pattern
- Make cards more summary-oriented and less generic
- Standardize chip, pill, and banner treatments

### 16.3 App Chrome

Update:

- `app/src/main/kotlin/com/openpod/navigation/OpenPodNavHost.kt`

Actions:

- Redesign bottom navigation surface and active indicator
- Define top-level scaffold behavior
- Standardize FAB or bottom action tray behavior

### 16.4 Feature Screens

Update first:

- `feature/dashboard/...`
- `feature/history/...`
- `feature/settings/...`
- `feature/pairing/...`
- `feature/onboarding/...`

Rollout order:

1. Theme and tokens
2. Shared shell and navigation
3. Dashboard as reference implementation
4. History and settings
5. Pairing and onboarding
6. Bolus and basal detail flows

## 17. Acceptance Criteria

The redesign is successful when:

- Dashboard can be understood in under 2 seconds
- Top-level screens share the same chrome and spacing language
- Cards, pills, and banners all feel like one family
- The app looks premium in both light and dark themes
- The app still reads as medical software, not a lifestyle dashboard
- New screens can be built by composing existing tokens and templates instead of inventing new patterns
