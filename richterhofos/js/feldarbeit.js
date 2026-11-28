/**
 * richterhofOS – Feldarbeit: Richtwert für Wirtschaftsdünger übernehmen.
 */
(function (Drupal, once) {
  Drupal.behaviors.richterhofosRichtwert = {
    attach(context) {
      once('rh-richtwert', 'select.rh-richtwert', context).forEach((auswahl) => {
        let werte = {};
        try { werte = JSON.parse(auswahl.dataset.richtwerte || '{}'); } catch (e) { return; }
        const form = auswahl.closest('form');
        const feld = (name) => form.querySelector(`[name="${name}"]`);
        let zuletzt = null;
        auswahl.addEventListener('change', () => {
          const w = werte[auswahl.value];
          if (!w) { return; }
          const [name, von, bis, einheit, art] = w;
          const duenger = feld('duengung[duenger]');
          if (duenger && (!duenger.value || duenger.dataset.rhAuto === '1')) {
            duenger.value = name; duenger.dataset.rhAuto = '1';
          }
          const n = feld('duengung[n_gehalt]');
          const mitte = String((von + bis) / 2).replace('.', ',');
          if (n && (!n.value || n.value === zuletzt)) {
            n.value = (von + bis) / 2; zuletzt = n.value;
          }
          const e = feld('duengung[menge][einheit]');
          if (e) { e.value = einheit; e.dispatchEvent(new Event('change', { bubbles: true })); }
          const radio = form.querySelector(`input[name="duengung[duengerart]"][value="${art}"]`);
          if (radio) { radio.checked = true; radio.dispatchEvent(new Event('change', { bubbles: true })); }
          auswahl.title = `${name}: ${von}–${bis} kg N/${einheit}, übernommen ${mitte}`;
        });
      });
    },
  };
})(Drupal, once);
