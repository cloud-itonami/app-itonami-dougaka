// Consent display patch for Dougaka Studio (dougaka layer only). The canonical
// consent broker behind this dialog (app/vendor/kotoba-client-runtime.js) is
// an immutable pinned upstream copy: it always prints the operation's
// endpoint origin and opens the modal dialog synchronously from inside the
// click's input dispatch. Two device-observed defects are compensated here,
// at the display layer, without touching the pin:
//
//  1. Browser-action (local) operations print the inert
//     https://local.itonami.cloud origin as "Endpoint:", which reads like a
//     network destination. The scope dump is rewritten before the modal
//     opens: the inert endpoint line is dropped and an explicit no-send line
//     takes its place. Allowing still sends nothing — the handler only
//     touches localStorage and never fetches.
//  2. showModal() ran synchronously inside input dispatch; on the Android
//     WebView this intermittently stalls input (ANR "Input dispatching timed
//     out"). The modal open is deferred to the next task so the input
//     pipeline drains first; the consent promise semantics are unchanged.

// One display line stating that even an approval transmits nothing.
export const NO_SEND_LINE = '送信: なし（この端末内で完結します。許可しても送信しません）';

/**
 * Rewrite the vendored consent scope dump for a browser-action consent:
 * drop the inert `Endpoint: <localOrigin>` line and put the no-send line in
 * its place. Network consents (any other endpoint) return byte-identical.
 */
export function rewriteLocalConsentScopeText(text, localOrigin) {
  const lines = String(text).split('\n');
  const index = lines.indexOf(`Endpoint: ${localOrigin}`);
  if (index === -1) return text;
  lines.splice(index, 1, NO_SEND_LINE);
  return lines.join('\n');
}

/**
 * Wrap the canonical consent dialog's showModal: rewrite a browser-action
 * scope dump before it shows, and defer the modal open to the next task so
 * the WebView input pipeline drains before the modal traps input. The
 * synchronous countdown of the runtime (deny.focus/access checks) keeps
 * working: the open only happens once, and a disconnected dialog keeps the
 * runtime's synchronous rejection path.
 */
export function installConsentDisplayFix({ dialog, localOrigin }) {
  if (!dialog || typeof dialog.showModal !== 'function') {
    throw new TypeError('a consent dialog element is required');
  }
  const open = dialog.showModal.bind(dialog);
  dialog.showModal = () => {
    const scope = typeof dialog.querySelector === 'function'
      ? dialog.querySelector('.kotoba-client-consent-scope')
      : null;
    if (scope) scope.textContent = rewriteLocalConsentScopeText(scope.textContent ?? '', localOrigin);
    if (!dialog.isConnected) { open(); return; } // runtime's catch rejects this synchronously
    setTimeout(() => {
      if (!dialog.isConnected || dialog.open) return;
      try { open(); } catch { return; } // a failed open leaves the consent pending; destroy() settles it
      const firstButton = typeof dialog.querySelector === 'function'
        ? dialog.querySelector('button')
        : null;
      firstButton?.focus?.();
    }, 0);
  };
}
