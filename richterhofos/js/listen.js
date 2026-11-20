/**
 * richterhofOS – „Alle“-Knopf je Feldgruppe und Drucken.
 */
(function (Drupal, once) {
  Drupal.behaviors.richterhofosListen = {
    attach(context) {
      once('rh-gruppe', 'details.rh-gruppe', context).forEach((gruppe) => {
        const summary = gruppe.querySelector('summary');
        if (!summary) {
          return;
        }
        const knopf = document.createElement('button');
        knopf.type = 'button';
        knopf.className = 'rh-alle';
        knopf.textContent = 'Alle';
        knopf.addEventListener('click', (e) => {
          e.preventDefault();
          e.stopPropagation();
          const boxen = Array.from(gruppe.querySelectorAll('input[type=checkbox]:not(:disabled)'));
          const alleAn = boxen.every((b) => b.checked);
          boxen.forEach((b) => { b.checked = !alleAn; });
          knopf.textContent = alleAn ? 'Alle' : 'Keine';
        });
        summary.appendChild(knopf);
      });
      once('rh-drucken', '.rh-drucken', context).forEach((k) => {
        k.addEventListener('click', () => window.print());
      });
    },
  };
})(Drupal, once);
