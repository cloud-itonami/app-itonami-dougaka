# Third-party licenses — app/vendor

The files in this directory are byte-identical copies vendored from
upstream repositories. Each file keeps its upstream license; the
`NOTICE` file in this directory is the upstream kotoba NOTICE,
propagated verbatim (Apache-2.0 §4(d)).

| File | Upstream | SPDX | Sha256 (see PINS.json) |
|---|---|---|---|
| `kotoba-client-runtime.js` | kotoba-lang/kotoba@889239dc, `lib/kotoba.wasmWebcomponent/src/kotoba-client-runtime.js` | Apache-2.0 | `7f760128…` |
| `kotoba-client-consent.css` | kotoba-lang/kotoba@889239dc, `lib/kotoba.wasmWebcomponent/src/kotoba-client-consent.css` | Apache-2.0 | `eb1caab0…` |
| `kotoba/lang/text.cljs` | kotoba-lang/text@bc551a3ecc68e551ba1dd2fcb22d5f1b8dd7805d, `src/kotoba/lang/text.cljk` | Apache-2.0 | `1443b860…` |
| `dds.css` | digital-go-jp/design-system-example-components-html@3b34f4c3553fa3bee90bfd8b6fe962ac3055107d, `resources/jp_go_dds/dds.css` (via jp-go-digital-design-system) | MIT | `86ed3735…` |

Full sha256 digests and per-file provenance are in `PINS.json`.
`scripts/build.mjs` refuses to build on pin drift, and
`test/vendor-notice.test.mjs` asserts the NOTICE propagation.

## vendor/kotoba-client-runtime.js

- Upstream: https://github.com/kotoba-lang/kotoba @ `889239dc`,
  path `lib/kotoba.wasmWebcomponent/src/kotoba-client-runtime.js`
- SPDX-License-Identifier: Apache-2.0
- Copyright: 2026 Jun Kawasaki (see `NOTICE`)
- This is the canonical client consent broker the Studio uses for every
  external operation (per-operation consent; undeclared operations are
  rejected). Vendored byte-identical via the published site build
  (`site/site.edn` `:client-runtime` pin
  `e00ae75e1c589f76b4f53a88a5e3256c98c9e162`).

## vendor/kotoba-client-consent.css

- Upstream: https://github.com/kotoba-lang/kotoba @ `889239dc`,
  path `lib/kotoba.wasmWebcomponent/src/kotoba-client-consent.css`
- SPDX-License-Identifier: Apache-2.0
- Copyright: 2026 Jun Kawasaki (see `NOTICE`)
- Stylesheet for the consent broker above; same upstream revision.

## vendor/kotoba/lang/text.cljs

- Upstream: https://github.com/kotoba-lang/text @
  `bc551a3ecc68e551ba1dd2fcb22d5f1b8dd7805d`, path
  `src/kotoba/lang/text.cljk`
- SPDX-License-Identifier: Apache-2.0
- Copyright: 2026 Jun Kawasaki (see `NOTICE`)
- The `kotoba.lang.text` string library the scittle timeline app
  requires; vendored byte-identical via the published site build
  (`site/site.edn` `:external` pin).

## vendor/dds.css

- Upstream: https://github.com/digital-go-jp/design-system-example-components-html
  @ `3b34f4c3553fa3bee90bfd8b6fe962ac3055107d`, path
  `resources/jp_go_dds/dds.css` (vendored through
  kotoba-lang/jp-go-digital-design-system)
- SPDX-License-Identifier: MIT
- Copyright (c) 2025 デジタル庁
- Vendored DADS stylesheet for the timeline-plan section. The upstream
  file header carries the same attribution; the full MIT license text
  is in the upstream repository.

## NOTICE

`NOTICE` in this directory is byte-identical to
https://github.com/kotoba-lang/kotoba `NOTICE` (364 bytes, sha256
`a263dafd3b98365765d784f139c44888ef6298d5b2b9ef6edbc5d84ffb86b0c9`),
covering the three Apache-2.0 files above. It is not rewritten or
summarized.
