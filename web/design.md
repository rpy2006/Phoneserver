# YouPI PG Demo — Design System Reference

> **Scope and evidence.** This document describes the checked-out source for the YouPI PG demo website. The public landing page is implemented at `/` in `app/src/routes/index.tsx`; its bespoke design rules are in `app/src/styles.css`. The source checkout was clean before this file was created. A browser screenshot attempt could not run because Playwright was not resolvable in the sandbox, so visual descriptions below are based on source code, assets, and component markup—not a claim that every deployed detail was visually verified. The deployed site and checkout source may differ; compare with the live page before relying on runtime-only details.
>
> **Change boundary:** this is documentation only. Do not treat it as authorization to change app source, styles, assets, dependencies, configuration, routes, or behavior.

## 1. Project overview

- **Product:** YouPI PG Demo — an educational/demo landing page for exploring a self-hosted UPI checkout concept.
- **Public route:** `/` (`app/src/routes/index.tsx`, TanStack file route).
- **Framework:** React 19 + TypeScript, TanStack Start and TanStack Router; Vite build pipeline.
- **Styling:** bespoke page CSS in `app/src/styles.css`, alongside Tailwind CSS v4, Higgsfield Quanta theme/utilities, and compatibility imports for shadcn-style components. The landing page itself primarily uses `yp-*` classes and ordinary CSS.
- **Icons:** `lucide-react` in the landing route.
- **Main visual language:** light cool-grey/paper canvas, dark navy typography, cobalt-blue emphasis, fine borders, restrained shadows, rounded cards, compact mono labels, and dark full-width feature bands. The checkout dialog is a contrasting white surface.
- **Important product boundary:** the checkout is a simulation. The page explicitly states that no real payment is initiated, no money moves, and dashboard data is illustrative. Preserve these disclaimers and never represent demo states as live payment verification.

## 2. Design principles reflected in the implementation

1. **Editorial hierarchy:** oversized, tightly tracked headings and short supporting copy establish the message before the technical detail.
2. **Cobalt emphasis:** `--yp-blue` marks highlighted heading fragments, primary calls to action, icons, and selected/active states.
3. **Quiet surfaces:** pale backgrounds, thin cool-grey borders, modest radii, and soft shadows separate content without heavy outlines.
4. **Technical metadata treatment:** eyebrow labels, section indices, dates, status labels, and code-card metadata use compact uppercase monospace styling and letter spacing.
5. **Product evidence over decoration:** desktop/mobile demo recordings, the merchant dashboard mockup, a workflow sequence, and an integration snippet explain the product.
6. **Repeated section rhythm:** headings and short explanatory text precede cards, flows, or code; sections use generous vertical padding on desktop and reduced spacing on mobile.
7. **Explicit demo safety:** “DEMO”, “Sample”, and no-real-payment copy appears in the checkout, dashboard, and footer.
8. **Responsive reflow:** multi-column layouts stack at the mobile breakpoint; navigation collapses to a menu button; dashboard detail is reduced on narrow screens.
9. **Motion with an escape hatch:** reveal effects, button feedback, and modal entrance animations exist, with a `prefers-reduced-motion` override.

These are implementation observations, not a claim that an external brand guide specifies them.

## 3. Color system

### 3.1 Explicit landing-page tokens

All values below are declared in `app/src/styles.css` in the `:root` block near the `YouPI PG premium fintech demo landing page` comment.

| Token / value | Exact value | Purpose in source | Source |
|---|---|---|---|
| `--yp-ink` | `#101827` | Main dark text; dark demo-stage background | `app/src/styles.css` |
| `--yp-paper` | `#f7f9fc` | Main page and site background | `app/src/styles.css` |
| `--yp-muted` | `#68758a` | Muted text token; many supporting labels use the same literal | `app/src/styles.css` |
| `--yp-blue` | `#526fe8` | Primary CTA, highlighted heading text, brand mark, active icon accents | `app/src/styles.css`, `app/src/scroll-scrub-scenes.ts` |
| `--yp-line` | `#e1e6ee` | Named line token | `app/src/styles.css` |
| `--yp-soft` | `#edf1f7` | Named soft surface token | `app/src/styles.css` |
| `--radius` | `0.625rem` | Base radius for the imported utility/theme layer; not a global radius for every bespoke landing component | `app/src/styles.css` |
| `--spacing` | `0.25rem` | Tailwind spacing restoration for generated/scaffold components | `app/src/styles.css` |

### 3.2 Other verified literal colors

The landing CSS uses additional direct values instead of routing every color through tokens. Preserve these established values when extending the same component family; do not assume they are all global semantic tokens.

| Value | Observed use | Source |
|---|---|---|
| `#405ed8` | Primary button hover background | `app/src/styles.css` (`.yp-btn-primary:hover`) |
| `#eef2f7` | Proof/capability strip background | `app/src/styles.css` (`.yp-proof-strip`) |
| `#fff` / `white` | Feature cards, button text, checkout modal surface | `app/src/styles.css` |
| `#f0f3fb` | First feature card surface | `app/src/styles.css` (`.yp-feature-main`) |
| `#e3e8f0`, `#e0e5ee`, `#e1e6ef` | Card/frame/control borders | `app/src/styles.css` |
| `#edf1ff` | Verification-flow icon tile background | `app/src/styles.css` |
| `#31a77a` | Green live/demo status dot | `app/src/styles.css` |
| `#39a67b` | Verification-flow success icon | `app/src/styles.css` |
| `#4d8b79` | Safety-note icon | `app/src/styles.css` |
| `#8fa2ff` | Highlight text on dark sections | `app/src/styles.css` |
| `#aab5c7` | Supporting copy on dark sections | `app/src/styles.css` |
| `#24324b` | Illustrative QR pattern cells | `app/src/styles.css` |
| `#fafbfd` | Amount panel and verification nodes | `app/src/styles.css` |
| `#f1f7f5` / `#507568` | Checkout safety notice background/text | `app/src/styles.css` |
| `#68b922` | Success toast style for generic scaffold UI | `app/src/styles.css`; not a YouPI landing checkout token |
| `#7a58ff` | “new” toast style for generic scaffold UI | `app/src/styles.css`; not a YouPI landing checkout token |

Other inline RGBA values are used for borders, overlays, shadows, and decorative orbits; see the component rules in `app/src/styles.css`. No light/dark theme toggle exists for the YouPI landing route. The landing CSS explicitly sets `color-scheme: light`; the shared root shell starts with a dark platform theme, but the landing route overrides its own canvas and text styling. Do not interpret the shared Quanta theme variables as the exact colors of the custom landing page.

## 4. Typography

### 4.1 Font families in the landing CSS

| Family declaration | Use | Notes |
|---|---|---|
| `'Space Grotesk', 'Avenir Next', sans-serif` | Landing body and primary UI text | Declared in `body`; the source inspected does not contain an `@font-face` or font import for Space Grotesk. Actual rendered font depends on the deployed environment/font availability. |
| `'IBM Plex Mono', monospace` | Eyebrows, brand DEMO tag, small metadata, code/status labels | Declared in component rules; no local font asset/import was found in the inspected `src/assets` and public YouPI assets. Actual rendered font is not guaranteed. |
| `var(--hf-type-family-secondary)` | Shared base body fallback before the landing-specific body rule | Provided by Quanta theme; the landing `body` rule later overrides it. |
| `var(--hf-type-family-grotesk)` / `--font-grotesk` | Shared Quanta/Tailwind theme mapping | Platform styling token, not the landing page's explicit font declaration. |

### 4.2 Verified type scale and patterns

Values below are examples of explicit declarations, not a complete normalized scale. The implementation uses component-specific values rather than a single formal typography token table.

| Element | Verified sizing/typography | Source |
|---|---|---|
| Hero H1 | `clamp(48px, 5.1vw, 70px)`, `line-height: .99`, weight `550`, tracking `-.068em` | `.yp-hero h1` |
| Section/workflow H2 | `clamp(40px, 4.5vw, 57px)`, `line-height: 1.02`, tracking `-.065em`, weight `540` | `.yp-section-heading h2`, `.yp-workflow-head h2` |
| Dark-stage/integration H2 | `clamp(42px, 4.6vw, 58px)`, `line-height: 1.02`, tracking `-.065em`, weight `540` | `.yp-stage-copy h2`, `.yp-integration-copy h2` |
| Hero body | `15px`, `line-height: 1.75` | `.yp-hero-sub` |
| Section explanatory copy | `14px`, `line-height: 1.75` | `.yp-section-heading > p` |
| Feature title | `25px`, `line-height: 1.12`, tracking `-.05em`, weight `550` | `.yp-feature h3` |
| Feature copy | `12px`, `line-height: 1.7` | `.yp-feature p` |
| Eyebrow | `10px`, IBM Plex Mono, weight `600`, tracking `.14em`, uppercase text in content | `.yp-eyebrow` |
| Primary button | `13px`, Space Grotesk, weight `550` | `.yp-btn-primary` |
| Checkout amount input | `30px`, Space Grotesk, weight `550`, tracking `-.055em` | `.yp-checkout-amount input` |
| Checkout result heading | `25px`, weight `550`, tracking `-.05em` | `.yp-checkout-result h2` |

Responsive typography overrides are declared in the `760px` and `380px` media queries. Preserve the existing `clamp()` and mobile overrides rather than flattening the headings to fixed sizes.

## 5. Spacing and layout system

There is no single spacing scale for the custom landing page. The source uses a combination of fixed pixel values, `clamp()`, `min()`, percentages, CSS Grid, and Flexbox. Do not claim that all spacing is derived from `--spacing`—that token belongs to the Tailwind compatibility layer.

| Pattern | Verified value / behavior | Source |
|---|---|---|
| Main content wrapper `.yp-wrap` | `width: min(1190px, calc(100% - 64px))`; centered with auto inline margins | `app/src/styles.css` |
| Header `.yp-nav` | `height: 78px`; max width `1440px`; horizontal padding `clamp(24px, 4.5vw, 68px)` | `app/src/styles.css` |
| Hero | Minimum height `675px`; top padding `91px`; bottom padding `72px`; two columns `.92fr 1.08fr`; responsive column gap | `app/src/styles.css` |
| Standard content section | `125px` top/bottom padding on desktop | `.yp-section` |
| Feature grid | Three columns `1.06fr 1fr .94fr`, `16px` gap | `.yp-feature-grid` |
| Feature card | Minimum height `410px`; padding `26px 24px 22px`; `12px` radius | `.yp-feature` |
| Dark demo stage | Two columns `.92fr 1.08fr`; `90px` gap; minimum height `740px`; `90px` vertical padding | `.yp-stage-inner` |
| Dashboard | Sidebar + main content grid; sidebar is hidden at mobile breakpoint | `.yp-dashboard-shell`, `.yp-dash-sidebar`, `.yp-dash-main` |
| Workflow | Four cards separated by arrows on desktop; one-column vertical sequence on mobile | `.yp-workflow-track` and media queries |
| Checkout modal | `width: min(100%, 430px)`; `max-height: calc(100dvh - 36px)`; internal vertical scroll | `.yp-checkout-modal` |
| Checkout amount panel | `17px` internal padding; `9px` radius; `1px` border | `.yp-checkout-amount` |
| Primary button | `14px 17px` padding, `7px` radius | `.yp-btn-primary` |
| Small screen wrapper | At `760px`, `width: min(100% - 36px, 540px)`; at `380px`, `calc(100% - 28px)` | `@media` rules |

The page also uses a fixed, low-opacity dot-grid texture on `body::before`, a fine grid overlay in the dark demo stage, orbit rings around the hero visual, and layered shadows around demo imagery. These are decorative CSS effects, not separate assets.

## 6. Design tokens and CSS conventions

### Landing-page tokens

| Token | Value | Role |
|---|---|---|
| `--yp-ink` | `#101827` | Primary ink / dark stage |
| `--yp-paper` | `#f7f9fc` | Main page background |
| `--yp-muted` | `#68758a` | Muted copy |
| `--yp-blue` | `#526fe8` | Brand/CTA accent |
| `--yp-line` | `#e1e6ee` | Named line color |
| `--yp-soft` | `#edf1f7` | Named soft color |

Defined in `app/src/styles.css`; `app/src/scroll-scrub-scenes.ts` repeats the accent/background/ink/muted colors for a retained template-compatible export, but `scrollScrubScenes` is an empty array.

### Styling architecture

- `app/src/styles.css` imports Tailwind v4, `@higgsfield/quanta/tailwind.css`, component styles, `tw-animate-css`, and `shadcn/tailwind.css`.
- The same stylesheet includes both platform/scaffold styling and the bespoke YouPI landing rules. The custom landing uses the `yp-` prefix (for example `.yp-nav`, `.yp-hero`, `.yp-feature`, `.yp-checkout-modal`).
- `@layer` order is declared as `reset, theme, base, components, utilities`.
- Tailwind source scanning includes `src`, `packages/app-landing/src`, and `packages/quanta/src`.
- Quanta semantic tokens such as `--hf-color-background-primary` are platform-level variables. Their exact resolved values are not declared in the landing-specific token block and should not be substituted for the explicit `--yp-*` palette.
- The custom CSS is mostly conventional component-prefixed CSS rather than CSS Modules or styled-components.
- `app/src/routes/__root.tsx` imports `../styles.css?url`, sets the HTML theme to `default-dark` for the platform shell, and defines global not-found/error boundaries. The landing route's own CSS explicitly sets a light `color-scheme` and light background.

## 7. Navigation system

### Header navigation

**Source:** `app/src/routes/index.tsx`, `app/src/styles.css`.

- Left: custom four-part blue mark, “YouPI PG” wordmark, and outlined `DEMO` tag.
- Center/right: anchor navigation to `#features` (“Platform”), `#flow` (“How it works”), `#dashboard` (“Dashboard”), and `#integration` (“Developers”).
- Actions: green-dot “Demo environment” status and “Try checkout” CTA.
- Header uses a flex layout, is `78px` high on desktop, has a thin bottom border, and uses `position: relative; z-index: 20`. It is **not** declared `position: sticky` or `fixed` in the inspected CSS.
- Navigation links transition color on hover to the blue accent.
- At `max-width: 760px`, the links are hidden and a menu icon appears. The menu button toggles the `is-open` class; the open panel is positioned below the header, uses a paper background, bottom border and shadow, and stacks links vertically. Clicking a navigation link closes the menu.
- The menu button has a changing accessible name (`Open menu` / `Close menu`).

### Footer

The footer repeats the brand mark and wordmark, includes a short positioning line, source repository link, year, and a full-width disclaimer stating that the page is an independent demo and does not accept or verify real payments. On mobile, the footer contents wrap and the tagline is moved to its own row.

### Navigation integration rule

New sections on the landing page should follow the existing anchor-navigation pattern and use IDs referenced by header links. Do not add routes or replace the current anchor navigation for a content-only section without an explicit request.

## 8. Page and section layouts

### Route `/` — `YouPiLanding`

Implemented by `YouPiLanding` in `app/src/routes/index.tsx`. The route is a single scrolling landing page with a modal overlay, not a multi-page marketing site.

1. **Header (`.yp-nav`)** — logo, anchor navigation, demo status, CTA, responsive menu.
2. **Hero (`.yp-hero`)** — eyebrow label, large two-line headline, supporting copy, primary “Try the demo” and secondary “View the flow” actions, safety note, desktop product-recording frame with overlay and caption.
3. **Proof strip (`.yp-proof-strip`)** — four capability statements with icons: self-hosted infrastructure, dynamic QR checkout, UPI app handoff, and payment verification.
4. **Features (`#features`)** — heading/copy and three cards for checkout, verification, and merchant control. Cards include a QR illustration, verification event flow, and a short list of control areas.
5. **Demo walkthrough (`#flow`)** — dark navy section with three numbered customer steps and a mobile device frame showing the mobile demo recording; includes a floating sample status.
6. **Merchant dashboard (`#dashboard`)** — heading/copy, mock sidebar, dashboard heading, `DEMO DATA` badge, three metric cards, payment filter tabs, transaction rows, and an explicit sample-data note.
7. **Underlying flow** — four stages connected by arrows: merchant checkout, UPI payment, verification, and returned outcome.
8. **Developer integration (`#integration`)** — dark section with explanatory copy, check-list, source-repository link, and a code card. The code card has “Copy snippet” feedback and clearly placeholder domain/merchant values.
9. **Final CTA** — centered brand mark, “Try the demo” action, decorative rings, and invitation to open the simulated checkout.
10. **Footer** — repeated branding, source link, copyright year, and demo disclaimer.
11. **Checkout overlay** — conditional dialog rendered over the page; see Section 9.

The primary landing route is the relevant design target for the public YouPI PG demo. The repository also contains a `/app` route (`app/src/routes/app.tsx`) which renders a generic Higgsfield `CustomTemplate` workspace/preview UI, and generated `/robots.txt` and `/sitemap.xml` routes. `/app` is not part of the described YouPI marketing-page hierarchy; inspect it separately before making any route-specific changes.

## 9. Reusable UI components and interaction states

The bespoke landing page is mostly composed directly in `YouPiLanding`; these are CSS/markup patterns, not exported shared React components except for the local `Mark` and `QrGraphic` helpers.

| Component / class | Appearance and role | Interaction/state | Source |
|---|---|---|---|
| `Mark` / `.yp-mark` | Four blue rounded tiles with differing corner radii/opacity, rotated slightly | Decorative; `aria-hidden="true"` | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-btn-primary` | Cobalt fill, white text, `7px` radius, subtle blue shadow | Hover darkens and lifts; active scales to `.98`; disabled opacity `.6` and `not-allowed` cursor | `app/src/styles.css` |
| `.yp-btn-text` | Unfilled text link with inline icon | Hover changes text to blue | `app/src/styles.css` |
| `.yp-btn-light` | Pale button on dark section with thin light border | Active scales to `.98`; transition on transform | `app/src/styles.css` |
| `.yp-nav-cta` | White button with grey-blue border and arrow icon | Hover border changes and button lifts slightly; active scales | `app/src/styles.css` |
| `.yp-feature` | White/soft surface, thin border, `12px` radius, vertical flex layout | Cards are informational; no click behavior declared | `app/src/styles.css`, `app/src/routes/index.tsx` |
| `.yp-visual-frame` | Product GIF inside bordered rounded frame with overlay label/action | Overlay action opens checkout demo | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-dashboard-shell` | Mock dashboard with sidebar, metrics, transaction rows | Tabs filter `All payments`, `Paid`, `Pending`; “View all” toggles extra sample rows | `app/src/routes/index.tsx` |
| `.yp-tx-status` | Status label with dot; paid/pending classes | Visual status only; data is hard-coded demo data | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-code-card` | Dark/code-oriented panel with line numbers, syntax-colored text, footer metadata | “Copy snippet” writes an illustrative snippet to clipboard; label changes to “Copied” for 1.8 seconds if successful | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-modal-backdrop` | Dark translucent, blurred full-screen backdrop | Click on backdrop closes dialog only when click target is the backdrop itself | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-checkout-modal` | White modal, dark ink text, `15px` radius, shadow, scrollable constrained height; max width `430px` | Opens with entrance animation; inner content changes by payment state | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-method-option` | Payment method row with branded-letter tile and radio-style indicator | Selecting a row updates `method` and selected class | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-secondary-outcome` | Secondary text-style checkout actions | Explicitly preview `pending` or `expired` state | `app/src/routes/index.tsx`, `app/src/styles.css` |
| `.yp-checkout-safety` | Pale green safety notice with shield icon | Static notice that the demo does not open a UPI app or create a payable QR | `app/src/routes/index.tsx`, `app/src/styles.css` |

### Checkout modal state model

`PaymentState` is a TypeScript union in `app/src/routes/index.tsx`:

- `ready`: amount entry, payment method choices, safety note, simulate action, and explicit pending/expired preview actions.
- `processing`: button shows a spinner and “Simulating payment…”. A `useEffect` timer changes state to success after **1400ms**.
- `success`: green success symbol, simulation disclaimer, amount, sample reference `YP-DEMO-2048`, selected method, sample verified-state label, and “Run another demo”.
- `pending`: explanatory sample pending message and “Start again”.
- `expired`: sample expired message and “Start again”.

Opening the demo sets the state to `ready`; closing it also resets state to `ready`. The amount defaults to `499`; input strips characters other than digits and a period and limits the string to eight characters. The primary action is disabled when the amount is empty or non-positive. This is a simulated state machine only, not a payment API integration.

### Component states not established

The bespoke landing page does not define a general-purpose set of dropdowns, tooltips, pagination controls, file uploads, or form validation patterns. Do not document or introduce these as existing design-system components unless the source is extended in a separate task.

## 10. Buttons and interaction states

- **Primary:** `.yp-btn-primary`; cobalt background and border, white text, `7px` radius, blue-tinted shadow; hover `#405ed8`, `translateY(-2px)`, larger shadow; active scale `.98`; disabled opacity `.6`.
- **Secondary text action:** `.yp-btn-text`; unfilled, muted text, inline icon; hover accent-blue text.
- **Light-section action:** `.yp-btn-light`; light background, light border, dark ink text; active scale `.98`.
- **Header CTA:** `.yp-nav-cta`; white surface, thin border; hover border `#9baae5` and `translateY(-1px)`.
- **Icon-only actions:** checkout close, responsive menu, and product-preview launch have accessible labels where shown in the JSX. Retain labels when modifying them.
- **Focus styling:** the amount input explicitly uses `:focus-visible` with a `2px` outline `#aebaf6`, `2px` offset, and `3px` radius. A page-wide bespoke focus style for every custom button/link was not found in the inspected landing CSS; do not claim it exists.

## 11. Forms and data entry

The only landing-page input is the checkout amount field (`#demo-amount`). It uses a text input with `inputMode="decimal"`, an accessible label, a rupee prefix, and an `INR` suffix. The controlled value starts at `499`; input is filtered to digits and periods and truncated to eight characters. No separate inline validation message is implemented. Instead, the main simulate button is disabled for empty/non-positive values.

The payment-method chooser is a group of buttons for Google Pay, PhonePe, Paytm, and Other UPI app. Selection is reflected by a `selected` class and radio-like visual indicator. It is not implemented as a native `<select>` or a radio input in the source inspected.

The developer code panel's “Copy snippet” action uses `navigator.clipboard.writeText`; on success, its label becomes `Copied` for 1800ms. On clipboard failure, it resets to `Copy snippet` without a visible error message.

## 12. Icons and visual assets

- **Icon library:** `lucide-react` imported directly by `app/src/routes/index.tsx`. Icons include `ArrowDown`, `ArrowRight`, `ArrowUpRight`, `Check`, `CheckCircle2`, `ChevronDown`, `CircleDollarSign`, `Clock3`, `Code2`, `Copy`, `CreditCard`, `ExternalLink`, `Fingerprint`, `Menu`, `QrCode`, `ShieldCheck`, `Smartphone`, `Sparkles`, `WalletCards`, `X`, and `Zap`.
- **Typical explicit icon sizes:** most inline actions use `14–18px`; feature icons use `21px`; workflow icons use `22px`; close icon uses `20px`. Preserve each usage's current sizing rather than forcing one universal size.
- **Brand mark:** CSS-generated four-square mark; no separate YouPI logo image is used in the landing markup.
- **Desktop recording:** `app/public/assets/youpi/desktop-demo.gif` (2,276,126 bytes in checked checkout); shown in hero.
- **Mobile recording:** `app/public/assets/youpi/mobile-demo.gif` (1,948,358 bytes in checked checkout); shown in the dark walkthrough's device frame.
- **Decorative source asset:** `app/src/assets/icon-sparkles-soft.svg` exists, but the main landing route does not import it in the inspected code.
- **Other public assets:** `app/public/assets/landing/template-preview.svg` and `app/public/presets/` assets exist in the broader app checkout; they are not referenced by the main YouPI landing route in the inspected markup.
- **QR illustration:** `QrGraphic` generates a fixed array of cells as DOM elements; CSS styles filled cells. It is explicitly labeled as an illustrative, non-payable QR pattern.

## 13. Responsive design

### Verified breakpoints

| Breakpoint | Observed changes | Source |
|---|---|---|
| `max-width: 1020px` | Header gaps/padding shrink; live badge is hidden; hero height and columns tighten; feature padding/type reduce; stage/integration gaps shrink; dashboard sidebar narrows; workflow cards compress | `app/src/styles.css` |
| `max-width: 760px` | Wrapper becomes `min(100% - 36px, 540px)`; mobile nav/menu appears; hero stacks; proof strip becomes 2-column grid; sections reduce vertical padding; feature cards stack; dark stage and integration become vertical; dashboard sidebar hides; transaction method column hides; workflow becomes vertical; footer wraps | `app/src/styles.css` |
| `max-width: 380px` | Wrapper gutters reduce to 14px each; header spacing/type shrink; hero H1 fixed at `43px`; dashboard header stacks; metric and transaction text compress further; secondary checkout action type reduces | `app/src/styles.css` |

### Layout behavior

- **Desktop/tablet-wide:** hero and dark walkthrough use two-column grids; feature cards use three columns; workflow uses four cards with horizontal arrows; dashboard includes a sidebar.
- **Mobile (`760px` and below):** header navigation is replaced by a toggle menu; hero and developer section stack; feature cards and workflow steps stack vertically; proof points use two columns; dashboard sidebar disappears and transaction payment-method details are hidden to conserve width.
- **Images:** desktop demo image uses `aspect-ratio: 1.56` on wide screens and `1.2` on mobile, with `object-fit: cover`; mobile demo recording is framed in a CSS device mockup.
- **Modal:** width capped at `430px`, uses viewport-relative max-height `calc(100dvh - 36px)`, and scrolls internally if content is taller than the available viewport.
- **Very small screens:** transaction columns and customer-name max widths are tightened at `380px`.
- No separate tablet breakpoint or exact touch-target audit is documented beyond the `1020px` and `760px` rules. Avoid inventing additional breakpoints.

## 14. Motion and animation

- **Smooth anchor scrolling:** `html { scroll-behavior: smooth; }`; disabled under reduced-motion preference.
- **Primary button:** `transition: transform .2s, box-shadow .2s, background .2s`.
- **Header CTA:** `transition: transform .2s, border-color .2s`.
- **Navigation links:** `transition: color .2s ease`.
- **Modal backdrop:** `yp-fade-in` animation, `.18s ease-out`.
- **Checkout modal:** `yp-modal-up`, `.24s cubic-bezier(.2,.75,.2,1)`, from opacity 0 and `translateY(14px) scale(.99)` to visible at normal position/scale.
- **Spinner:** `yp-spin` rotates to `360deg`; its duration is defined in the modal rules and should be read from the current `.yp-spinner` declaration before editing (do not infer a duration from the keyframe alone).
- **Scroll reveal:** IntersectionObserver adds `.yp-reveal`; elements transition opacity and vertical offset over `.65s`, using `ease` for opacity and `cubic-bezier(.2,.7,.2,1)` for transform. Feature and workflow items have staggered delays of `90ms`, `160ms`, `80ms`, `140ms`, and `200ms` where specified. If reduced motion is requested or IntersectionObserver is unavailable, targets are made visible directly.
- **Reduced motion:** `@media (prefers-reduced-motion: reduce)` reduces global animation/transition duration to `.01ms`, disables smooth scrolling, and ensures reveal targets remain visible without transforms/transitions.

No theme-switch animation or page-route transition was identified for the landing page.

## 15. Accessibility

### Verified implementation details

- Root document declares `lang="en"` in `app/src/routes/__root.tsx`.
- Header navigation uses `<nav aria-label="Main navigation">`.
- Main page has `<main id="home">`, semantic sections, a header, and footer.
- Logo link has `aria-label="YouPI PG home"`; mobile menu and checkout-close buttons have changing/explicit accessible labels.
- Desktop/mobile recording images have descriptive `alt` text.
- Checkout overlay uses `role="dialog"`, `aria-modal="true"`, and `aria-labelledby="checkout-title"` in the markup. The inspected markup should be checked to ensure the referenced `checkout-title` actually exists; do not claim focus trapping/return behavior without additional implementation evidence.
- Amount field has `label htmlFor="demo-amount"` and an `aria-label`.
- Decorative logo mark is hidden from assistive technology using `aria-hidden="true"`.
- Reduced-motion preference is explicitly supported.

### Not verified / caution

- No explicit global custom focus treatment was found for all buttons and links in the landing CSS; only the amount input's `:focus-visible` rule is explicit in the inspected bespoke styles.
- Dialog focus trapping, Escape-to-close, focus restoration, and scroll locking were not evident in the shown route implementation. Do not claim these behaviors exist.
- Color contrast has not been formally measured; do not claim WCAG conformance.
- Dashboard tab buttons show selected styling through the `active` class; their tab semantics/ARIA roles were not present in the inspected snippet.
- Preserve existing semantic labels and alt text. Accessibility improvements should be a separate, scoped implementation task rather than silently folded into documentation changes.

## 16. Content and visual hierarchy

- Eyebrow labels precede headings, usually uppercase mono with wide letter spacing.
- Main headlines use deliberate line breaks and color the key phrase blue; the dark sections use a lighter blue (`#8fa2ff`) for emphasis.
- Supporting paragraphs are muted and constrained to moderate line lengths.
- CTAs appear after the main copy, while demo safety notes sit close to payment-related actions.
- Feature cards use a numbered category label, title, short description, and a visual or short list anchored near the bottom of the card.
- The dashboard places the sample-data badge and metrics above transaction filters and rows.
- Code examples are styled as a compact editor panel with line numbers, a filename, a copy control, and a footer that marks the snippet as illustrative.
- Payment status is communicated with both text and a colored dot/indicator; preserve the written status labels, not color alone.
- The page repeatedly discloses that payment results and dashboard records are samples.

## 17. Source file reference

Paths are relative to the repository root unless prefixed by `app/`.

| Design element | Source file | Notes |
|---|---|---|
| Main landing page and route `/` | `app/src/routes/index.tsx` | `YouPiLanding`, `Mark`, `QrGraphic`, all sections, checkout state, interactions |
| Landing design tokens and all bespoke page styles | `app/src/styles.css` | `--yp-*` variables, component classes, responsive and motion rules |
| Shared HTML shell, head metadata, error and not-found states | `app/src/routes/__root.tsx` | Imports global stylesheet; root theme metadata; root error boundary |
| Router creation / scroll restoration | `app/src/router.tsx` | TanStack Router configuration |
| Route tree | `app/src/routeTree.gen.ts` | Generated route inventory; do not edit by hand |
| App metadata | `app/src/app-meta.json` | Browser title/description, social image and marketplace cover metadata |
| Landing content metadata / unused scenes export | `app/src/scroll-scrub-scenes.ts` | Repeats brand colors; scene array currently empty |
| Desktop demo recording | `app/public/assets/youpi/desktop-demo.gif` | Hero media |
| Mobile demo recording | `app/public/assets/youpi/mobile-demo.gif` | Mobile checkout walkthrough media |
| Package/dependency definitions | `app/package.json` | React, TanStack, Tailwind, Lucide, Quanta, and other packages |
| Vite/Tailwind integration | `app/vite.config.ts` | Build and plugin setup |
| Generic workspace route | `app/src/routes/app.tsx` | `/app` renders `CustomTemplate`; not the public YouPI landing design |
| Generic workspace layout | `app/src/layouts/custom.tsx` | Separate template UI; uses Quanta components and Lucide/Phosphor icons |
| Generic landing/package components | `app/packages/app-landing/src/landing-page.tsx` and `app/packages/app-landing/src/index.ts` | Workspace package exists; main `/` route does not use it in the inspected imports |

## 18. Design consistency rules for future development

1. **Treat `app/src/styles.css` as the source of truth for the YouPI landing visual language.** Inspect existing selectors before adding styles.
2. **Reuse `--yp-*` values.** Do not invent a second blue, ink, paper, muted-text, or border palette when the existing values apply.
3. **Continue the `yp-` class prefix** for new bespoke landing styles to avoid colliding with Tailwind, Quanta, or scaffold styles.
4. **Follow the existing hierarchy:** eyebrow → headline → supporting copy → action or content visual, where that pattern fits the section being extended.
5. **Reuse the current CTA treatments** (`.yp-btn-primary`, `.yp-btn-text`, `.yp-btn-light`, `.yp-nav-cta`) rather than creating near-duplicate buttons.
6. **Use the existing icon dependency (`lucide-react`)** and match current icon sizing/placement. Do not add another icon library for a small UI addition.
7. **Preserve the established responsive breakpoints** at `1020px`, `760px`, and `380px` unless a separately scoped requirement proves a new breakpoint is necessary.
8. **Preserve motion and reduced-motion behavior.** Any new reveal/transition must not leave content hidden when reduced motion is enabled.
9. **Keep the checkout explicitly simulated.** Do not remove disclaimers or imply real UPI handoff, QR payment, bank verification, or live dashboard data.
10. **Keep interactions truthful and consistent.** New interactive controls need clear active/disabled feedback and must not imply backend functionality that is not implemented.
11. **Preserve section anchor behavior** and close the mobile menu after choosing a navigation link.
12. **Reuse actual assets.** Do not replace the existing desktop/mobile demo recordings with generated placeholders or unrelated imagery.
13. **Do not assume every dependency is used on the landing page.** `app/package.json` includes broad platform dependencies; only add imports where needed and avoid unnecessary packages.
14. **Do not change the shared `/app` template while making a landing-page change**, and vice versa, unless the task explicitly covers both.
15. **Document unknowns honestly.** If an exact rendered font, contrast ratio, runtime-only state, or deployed-only difference cannot be confirmed, label it unverified rather than guessing.

## 19. Implementation checklist

Before submitting a future UI change:

- [ ] Read the relevant section of this document and inspect the current JSX/CSS.
- [ ] Reused existing `--yp-*` tokens and established component classes where appropriate.
- [ ] Preserved typography hierarchy, muted copy, mono metadata, borders, radii, and shadow treatment.
- [ ] Checked the layout at widths around `1020px`, `760px`, and `380px`.
- [ ] Checked mobile navigation and anchor behavior if the header was touched.
- [ ] Checked disabled, hover, active, focus, loading, success, pending, and expired states that apply to the modified control.
- [ ] Preserved the reduced-motion path and ensured revealed content remains visible.
- [ ] Considered keyboard/screen-reader labels and semantic markup.
- [ ] Kept the sample-payment disclaimer and dashboard-data disclaimer accurate.
- [ ] Avoided changes to unrelated routes, the generic `/app` workspace, dependencies, and application behavior.
- [ ] Ran the relevant existing checks if source was changed; report their result rather than claiming unrun checks passed.

## 20. Known limitations and unverified details

- The source checkout is available and was inspected, but a live browser screenshot could not be captured in this environment because the Playwright module was not resolvable. Thus the document distinguishes source-derived facts from deployed visual confirmation.
- The live deployment was not proven byte-for-byte identical to this checkout.
- No `@font-face` or explicit font import for Space Grotesk or IBM Plex Mono was found in the inspected landing stylesheet/assets. The CSS declares those families, but actual font availability and rendered fallback are unverified.
- Resolved values for Quanta's `--hf-*` theme tokens are imported from the Quanta package and are not exhaustively enumerated here; they are not the same as the landing's explicit `--yp-*` variables.
- The bespoke landing CSS contains many direct color/spacing values rather than a complete formal token system; the tables list verified representative values, not every literal in the stylesheet.
- Accessibility details such as modal focus management, global focus visibility, and formal contrast compliance were not established by the inspected source.
- The route tree includes `/app`, `/robots.txt`, and `/sitemap.xml`; `/app` renders a separate generic template, and the XML/text routes are not visual marketing pages.
- No source files, CSS, assets, route configuration, package files, dependencies, or runtime behavior were changed for this documentation task.
