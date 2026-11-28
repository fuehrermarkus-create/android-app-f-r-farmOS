<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Form;

use Drupal\Core\Datetime\DrupalDateTime;
use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\Core\Url;
use Drupal\richterhofos\Aufzeichnung;
use Drupal\richterhofos\Hof;

/**
 * Feldarbeit im Browser erfassen: Düngung, Pflanzenschutz, Aussaat.
 *
 * Schritt 1: Arbeit, Zeit, Mittel/Dünger/Frucht, Menge (je ha oder gesamt) und
 * mehrere Felder wählen. Schritt 2: Kontrolltabelle je Feld mit änderbarer
 * Fläche, dann speichern. Pro Feld entsteht ein eigener Eintrag – genau so, wie
 * ihn die App anlegt (gleiche Kategorien, Mengen und Daten im Feld „data“), damit
 * App, Listen und Aufzeichnungen ihn gleich behandeln.
 */
class FeldarbeitForm extends FormBase {

  const ARBEITEN = [
    'duengung' => ['Düngung', 'input', 'Düngung'],
    'pflanzenschutz' => ['Pflanzenschutz', 'input', 'Pflanzenschutz'],
    'aussaat' => ['Aussaat', 'seeding', 'Aussaat'],
  ];

  const DUENGERARTEN = ['Gülle / Jauche', 'Mist / Kompost', 'Mineraldünger', 'Gärrest', 'Sonstiger organischer'];
  const TECHNIKEN = ['Schleppschlauch', 'Schleppschuh', 'Injektion / Schlitz', 'Breitverteiler', 'Düngerstreuer', 'Miststreuer'];

  const EPPO = [
    'silomais' => 'ZEAMX', 'körnermais' => 'ZEAMX', 'mais' => 'ZEAMX',
    'winterweizen' => 'TRZAW', 'sommerweizen' => 'TRZAS', 'weizen' => 'TRZAX',
    'wintergerste' => 'HORVW', 'sommergerste' => 'HORVS', 'gerste' => 'HORVX',
    'winterroggen' => 'SECCW', 'roggen' => 'SECCE', 'hafer' => 'AVESA',
    'triticale' => 'TTLSP', 'dinkel' => 'TRZSP', 'winterraps' => 'BRSNW', 'raps' => 'BRSNN',
    'sojabohne' => 'GLXMA', 'soja' => 'GLXMA', 'kartoffel' => 'SOLTU', 'zuckerrübe' => 'BEAVA',
    'sonnenblume' => 'HELAN', 'ackerbohne' => 'VICFX', 'erbse' => 'PIBSX',
  ];

  public function getFormId(): string {
    return 'richterhofos_feldarbeit_form';
  }

  public function buildForm(array $form, FormStateInterface $form_state): array {
    $form['#attached']['library'][] = 'richterhofos/feldarbeit';
    $form['#attributes']['class'][] = 'rh-feldarbeit';
    if ($form_state->get('schritt') === 2) {
      return $this->kontrolle($form, $form_state);
    }
    $alt = $form_state->get('eingabe') ?? [];
    $arbeit = $alt['arbeit'] ?? (string) $this->getRequest()->query->get('arbeit', 'duengung');
    if (!isset(self::ARBEITEN[$arbeit])) {
      $arbeit = 'duengung';
    }

    $form['arbeit'] = [
      '#type' => 'radios',
      '#title' => 'Was wurde gemacht?',
      '#options' => array_map(fn($a) => $a[0], self::ARBEITEN),
      '#default_value' => $arbeit,
      '#attributes' => ['class' => ['rh-auswahlknoepfe', 'rh-auswahlknoepfe--gross']],
    ];
    $form['zeit'] = [
      '#type' => 'datetime',
      '#title' => 'Wann?',
      '#default_value' => DrupalDateTime::createFromTimestamp($alt['zeit'] ?? \Drupal::time()->getRequestTime()),
      '#required' => TRUE,
    ];

    $sichtbar = fn(string $a) => ['visible' => [':input[name="arbeit"]' => ['value' => $a]]];

    // ---- Düngung
    $form['duengung'] = [
      '#type' => 'fieldset',
      '#title' => 'Düngung',
      '#tree' => TRUE,
      '#states' => $sichtbar('duengung'),
      'duenger' => $this->material('Dünger', 'z. B. Rindergülle, Mist, NAC 27', $alt['duengung']['duenger'] ?? ''),
      'duengerart' => [
        '#type' => 'radios',
        '#title' => 'Art des Düngers',
        '#options' => array_combine(self::DUENGERARTEN, self::DUENGERARTEN),
        '#default_value' => $alt['duengung']['duengerart'] ?? NULL,
        '#attributes' => ['class' => ['rh-auswahlknoepfe']],
      ],
      'menge' => $this->menge($alt['duengung'] ?? [], ['m³' => 'm³', 't' => 't', 'kg' => 'kg'], 'Ausgebrachte Menge',
        'Gülle: m³, Mist: t, Mineraldünger: kg. „Gesamt“ wird nach der Fläche auf die Felder aufgeteilt.'),
      'n_gehalt' => [
        '#type' => 'number',
        '#title' => 'N-Gehalt (kg N je m³ bzw. t, bei kg: % N)',
        '#description' => 'Jahreswirksamer Stickstoff laut Analyse, Sack oder Richtwert. Leer = zuletzt verwendeter Wert für diesen Dünger.',
        '#step' => 'any',
        '#min' => 0,
        '#default_value' => $alt['duengung']['n_gehalt'] ?? NULL,
      ],
      'technik' => [
        '#type' => 'select',
        '#title' => 'Ausbringtechnik',
        '#options' => array_combine(self::TECHNIKEN, self::TECHNIKEN),
        '#empty_option' => '– keine Angabe –',
        '#default_value' => $alt['duengung']['technik'] ?? NULL,
      ],
      'eingearbeitet' => [
        '#type' => 'checkbox',
        '#title' => 'Eingearbeitet',
        '#default_value' => !empty($alt['duengung']['einarbeitung']),
      ],
      'einarbeitung' => [
        '#type' => 'datetime',
        '#title' => 'Eingearbeitet am',
        '#default_value' => DrupalDateTime::createFromTimestamp($alt['duengung']['einarbeitung'] ?? (\Drupal::time()->getRequestTime() + 3600)),
        '#states' => ['visible' => [':input[name="duengung[eingearbeitet]"]' => ['checked' => TRUE]]],
      ],
    ];

    // ---- Pflanzenschutz
    $form['pflanzenschutz'] = [
      '#type' => 'fieldset',
      '#title' => 'Pflanzenschutz',
      '#tree' => TRUE,
      '#states' => $sichtbar('pflanzenschutz'),
      'mittel' => $this->material('Pflanzenschutzmittel', 'Handelsname wie am Etikett', $alt['pflanzenschutz']['mittel'] ?? ''),
      'zulassungsnummer' => [
        '#type' => 'textfield',
        '#title' => 'Zulassungsnummer',
        '#description' => 'Etikett oder Pflanzenschutzmittelregister, z. B. 3045-0. Leer = zuletzt verwendete Nummer für dieses Mittel.',
        '#size' => 20,
        '#default_value' => $alt['pflanzenschutz']['zulassungsnummer'] ?? '',
      ],
      'menge' => $this->menge($alt['pflanzenschutz'] ?? [], ['l' => 'l', 'kg' => 'kg', 'g' => 'g'], 'Aufwandmenge', 'Meist je ha laut Zulassung.'),
      'wasser' => [
        '#type' => 'number',
        '#title' => 'Wasser (l/ha)',
        '#step' => 'any',
        '#min' => 0,
        '#default_value' => $alt['pflanzenschutz']['wasser'] ?? NULL,
      ],
      'grund' => [
        '#type' => 'textfield',
        '#title' => 'Grund / Schaderreger',
        '#default_value' => $alt['pflanzenschutz']['grund'] ?? '',
      ],
      'anwender' => [
        '#type' => 'textfield',
        '#title' => 'Anwender',
        '#default_value' => $alt['pflanzenschutz']['anwender'] ?? $this->currentUser()->getDisplayName(),
      ],
      'bbch' => [
        '#type' => 'textfield',
        '#title' => 'BBCH-Stadium (nur wenn die Zulassung es verlangt)',
        '#size' => 8,
        '#default_value' => $alt['pflanzenschutz']['bbch'] ?? '',
      ],
    ];

    // ---- Aussaat
    $form['aussaat'] = [
      '#type' => 'fieldset',
      '#title' => 'Aussaat',
      '#tree' => TRUE,
      '#states' => $sichtbar('aussaat'),
      'frucht' => [
        '#type' => 'textfield',
        '#title' => 'Frucht',
        '#description' => 'z. B. Silomais, Winterweizen, Kleegras. Bekannte Früchte werden vorgeschlagen.',
        '#autocomplete_route_name' => 'richterhofos.vorschlag',
        '#autocomplete_route_parameters' => ['vokabular' => 'plant_type'],
        '#default_value' => $alt['aussaat']['frucht'] ?? '',
      ],
      'sorte' => [
        '#type' => 'textfield',
        '#title' => 'Sorte',
        '#default_value' => $alt['aussaat']['sorte'] ?? '',
      ],
      'menge' => $this->menge($alt['aussaat'] ?? [], ['kg' => 'kg', 'Körner/m²' => 'Körner/m²'], 'Saatmenge', NULL, TRUE),
    ];

    // ---- Maschinen
    $maschinen = [];
    foreach (Hof::etm()->getStorage('asset')->loadMultiple(Hof::aktiveDinge('equipment')->execute()) as $m) {
      $maschinen[$m->id()] = $m->label();
    }
    natcasesort($maschinen);
    if ($maschinen) {
      $form['maschinen'] = [
        '#type' => 'checkboxes',
        '#title' => 'Eingesetzte Maschinen (optional)',
        '#options' => $maschinen,
        '#default_value' => $alt['maschinen'] ?? [],
        '#attributes' => ['class' => ['rh-mehrspaltig']],
      ];
    }

    // ---- Felder
    $form['felder'] = $this->felderAuswahl($alt['felder'] ?? []);

    $form['notiz'] = [
      '#type' => 'textarea',
      '#title' => 'Notiz (optional)',
      '#rows' => 2,
      '#default_value' => $alt['notiz'] ?? '',
    ];
    $form['aktionen'] = ['#type' => 'actions'];
    $form['aktionen']['weiter'] = [
      '#type' => 'submit',
      '#value' => 'Weiter zur Kontrolle',
      '#button_type' => 'primary',
      '#submit' => ['::zurKontrolle'],
    ];
    return $form;
  }

  /**
   * Eingabefeld für Mittel/Dünger mit Vorschlägen aus farmOS.
   */
  protected function material(string $titel, string $hinweis, string $wert): array {
    return [
      '#type' => 'textfield',
      '#title' => $titel,
      '#description' => $hinweis,
      '#autocomplete_route_name' => 'richterhofos.vorschlag',
      '#autocomplete_route_parameters' => ['vokabular' => 'material_type'],
      '#default_value' => $wert,
    ];
  }

  /**
   * Menge mit Einheit und Auswahl „je ha“ / „gesamt“.
   */
  protected function menge(array $alt, array $einheiten, string $titel, ?string $hinweis, bool $nur_je_ha = FALSE): array {
    $teil = [
      '#type' => 'container',
      '#attributes' => ['class' => ['rh-mengenzeile']],
      'wert' => [
        '#type' => 'number',
        '#title' => $titel,
        '#step' => 'any',
        '#min' => 0,
        '#default_value' => $alt['menge']['wert'] ?? NULL,
        '#description' => $hinweis,
      ],
      'einheit' => [
        '#type' => 'select',
        '#title' => 'Einheit',
        '#options' => $einheiten,
        '#default_value' => $alt['menge']['einheit'] ?? array_key_first($einheiten),
      ],
    ];
    if (!$nur_je_ha) {
      $teil['bezug'] = [
        '#type' => 'radios',
        '#title' => 'Bezug',
        '#options' => ['ha' => 'je ha', 'gesamt' => 'gesamt für alle Felder'],
        '#default_value' => $alt['menge']['bezug'] ?? 'ha',
        '#attributes' => ['class' => ['rh-auswahlknoepfe']],
      ];
    }
    return $teil;
  }

  /**
   * Felder nach aktueller Frucht gruppiert, mit Größe.
   */
  protected function felderAuswahl(array $gewaehlt): array {
    $kulturen = Hof::kulturenJeFeld();
    $blueh = array_flip(Hof::bluehflaechen());
    $gruppen = [];
    foreach (Hof::felder() as $id => $feld) {
      $arten = array_unique(array_column($kulturen[$id] ?? [], 'art'));
      $gruppe = isset($blueh[(string) $id]) ? '🌼 Blühflächen' : ($arten ? implode(', ', $arten) : 'Ohne Kultur');
      $ha = Aufzeichnung::feldgroesse($feld);
      $gruppen[$gruppe][$id] = $feld->label() . ($ha ? ' · ' . Aufzeichnung::z($ha, 2) . ' ha' : '');
    }
    $rang = fn($g) => $g === 'Ohne Kultur' ? 2 : ($g === '🌼 Blühflächen' ? 1 : 0);
    uksort($gruppen, fn($a, $b) => [$rang($a), $a] <=> [$rang($b), $b]);

    $teil = [
      '#type' => 'container',
      '#tree' => TRUE,
      'titel' => ['#markup' => '<h3 class="rh-abschnitt">Auf welchen Feldern?</h3><p class="rh-hinweis">Mehrere Felder mit derselben Arbeit auf einmal. Nach der Frucht gruppiert; „Alle“ wählt die ganze Gruppe.</p>'],
    ];
    $n = 0;
    foreach ($gruppen as $gruppe => $felder) {
      $teil['g' . $n++] = [
        '#type' => 'details',
        '#title' => $gruppe . ' (' . count($felder) . ')',
        '#open' => TRUE,
        '#attributes' => ['class' => ['rh-gruppe']],
        'auswahl' => [
          '#type' => 'checkboxes',
          '#options' => $felder,
          '#default_value' => array_values(array_intersect(array_map('strval', array_keys($felder)), array_map('strval', $gewaehlt))),
        ],
      ];
    }
    return $teil;
  }

  protected function gewaehlteFelder(FormStateInterface $form_state): array {
    $ids = [];
    foreach ((array) $form_state->getValue('felder') as $gruppe) {
      foreach ((array) ($gruppe['auswahl'] ?? []) as $id => $an) {
        if ($an) {
          $ids[] = (string) $id;
        }
      }
    }
    return array_values(array_unique($ids));
  }

  public function validateForm(array &$form, FormStateInterface $form_state): void {
    if ($form_state->get('schritt') === 2) {
      return;
    }
    $arbeit = (string) $form_state->getValue('arbeit');
    $teil = (array) $form_state->getValue($arbeit);
    if (!$this->gewaehlteFelder($form_state)) {
      $form_state->setErrorByName('felder', 'Bitte mindestens ein Feld auswählen.');
    }
    $menge = $teil['menge']['wert'] ?? '';
    if ($arbeit === 'duengung') {
      if (trim((string) $teil['duenger']) === '') {
        $form_state->setErrorByName('duengung][duenger', 'Bitte den Dünger angeben.');
      }
      if ($menge === '' || $menge === NULL) {
        $form_state->setErrorByName('duengung][menge][wert', 'Bitte die ausgebrachte Menge angeben.');
      }
    }
    elseif ($arbeit === 'pflanzenschutz') {
      $mittel = trim((string) $teil['mittel']);
      if ($mittel === '') {
        $form_state->setErrorByName('pflanzenschutz][mittel', 'Bitte das Pflanzenschutzmittel angeben.');
      }
      elseif (trim((string) $teil['zulassungsnummer']) === '' && !($this->letzte($mittel)['zulassungsnummer'] ?? NULL)) {
        $form_state->setErrorByName('pflanzenschutz][zulassungsnummer', 'Für „' . $mittel . '“ ist noch keine Zulassungsnummer bekannt – bitte eingeben.');
      }
      if ($menge === '' || $menge === NULL) {
        $form_state->setErrorByName('pflanzenschutz][menge][wert', 'Bitte die Aufwandmenge angeben.');
      }
      if (trim((string) $teil['anwender']) === '') {
        $form_state->setErrorByName('pflanzenschutz][anwender', 'Bitte den Anwender angeben.');
      }
    }
    elseif (trim((string) ($teil['frucht'] ?? '')) === '') {
      $form_state->setErrorByName('aussaat][frucht', 'Bitte die Frucht angeben.');
    }
  }

  /**
   * Schritt 1 → 2: Eingaben merken, Kontrolltabelle zeigen.
   */
  public function zurKontrolle(array &$form, FormStateInterface $form_state): void {
    $arbeit = (string) $form_state->getValue('arbeit');
    $teil = (array) $form_state->getValue($arbeit);
    $zeit = $form_state->getValue('zeit');
    $eingabe = [
      'arbeit' => $arbeit,
      'zeit' => $zeit instanceof DrupalDateTime ? $zeit->getTimestamp() : \Drupal::time()->getRequestTime(),
      'felder' => $this->gewaehlteFelder($form_state),
      'maschinen' => array_values(array_filter((array) $form_state->getValue('maschinen'))),
      'notiz' => trim((string) $form_state->getValue('notiz')),
      $arbeit => $teil,
    ];
    if ($arbeit === 'duengung') {
      $e = $teil['einarbeitung'] ?? NULL;
      $eingabe['duengung']['einarbeitung'] = (!empty($teil['eingearbeitet']) && $e instanceof DrupalDateTime) ? $e->getTimestamp() : NULL;
      unset($eingabe['duengung']['eingearbeitet']);
      $letzte = $this->letzte(trim((string) $teil['duenger']));
      if (($teil['n_gehalt'] ?? '') === '' && isset($letzte['n_gehalt'])) {
        $eingabe['duengung']['n_gehalt'] = $letzte['n_gehalt'];
        $this->messenger()->addStatus('N-Gehalt ' . Aufzeichnung::z((float) $letzte['n_gehalt']) . ' vom letzten Mal übernommen.');
      }
      if (empty($teil['duengerart']) && !empty($letzte['duengerart'])) {
        $eingabe['duengung']['duengerart'] = $letzte['duengerart'];
      }
    }
    if ($arbeit === 'pflanzenschutz' && trim((string) $teil['zulassungsnummer']) === '') {
      $eingabe['pflanzenschutz']['zulassungsnummer'] = $this->letzte(trim((string) $teil['mittel']))['zulassungsnummer'] ?? '';
    }
    $form_state->set('eingabe', $eingabe);
    $form_state->set('schritt', 2);
    $form_state->setRebuild();
  }

  /**
   * Schritt 2: Kontrolle je Feld, Fläche änderbar.
   */
  protected function kontrolle(array $form, FormStateInterface $form_state): array {
    $e = $form_state->get('eingabe');
    $arbeit = $e['arbeit'];
    $teil = $e[$arbeit];
    [$titel] = self::ARBEITEN[$arbeit];
    $felder = Hof::etm()->getStorage('asset')->loadMultiple($e['felder']);
    uasort($felder, fn($a, $b) => strnatcasecmp((string) $a->label(), (string) $b->label()));
    $kulturen = Hof::kulturenJeFeld();

    $was = match ($arbeit) {
      'duengung' => trim((string) $teil['duenger']) . ($teil['duengerart'] ? ' (' . $teil['duengerart'] . ')' : ''),
      'pflanzenschutz' => trim((string) $teil['mittel']) . ' · Zulassung ' . ($teil['zulassungsnummer'] ?: '–'),
      default => trim((string) $teil['frucht']) . ($teil['sorte'] ? ' ' . trim((string) $teil['sorte']) : ''),
    };
    $menge = $teil['menge'];
    $bezug = ($menge['bezug'] ?? 'ha') === 'gesamt' ? 'gesamt' : 'je ha';
    $form['kopf'] = [
      '#type' => 'inline_template',
      '#template' => '<div class="rh-kontrollkopf"><span class="rh-marke">{{ titel }}</span> <strong>{{ was }}</strong>'
        . '<p>{{ zeit }}{% if menge %} · {{ menge }}{% endif %}</p>'
        . '<p class="rh-hinweis">Bitte prüfen. Die Fläche je Feld kannst du ändern, z. B. wenn nur ein Teil gedüngt wurde.</p></div>',
      '#context' => [
        'titel' => $titel,
        'was' => $was,
        'zeit' => date('d.m.Y, H:i', (int) $e['zeit']) . ' Uhr',
        'menge' => ($menge['wert'] ?? '') !== '' ? Aufzeichnung::z((float) $menge['wert']) . ' ' . $menge['einheit'] . ' ' . $bezug : '',
      ],
    ];

    $kopf = ['Feld', 'Frucht', 'Fläche ha'];
    if ($arbeit !== 'aussaat') {
      $kopf[] = 'Menge je ha';
      $kopf[] = 'Menge';
    }
    if ($arbeit === 'duengung') {
      $kopf[] = 'kg N/ha';
    }
    $form['zeilen'] = [
      '#type' => 'table',
      '#header' => $kopf,
      '#tree' => TRUE,
      '#attributes' => ['class' => ['rh-tabelle', 'rh-kontrolle']],
    ];
    $flaechen = (array) ($form_state->get('flaechen') ?? []);
    $rechnung = $this->rechnen($e, $felder, $flaechen);
    foreach ($felder as $id => $feld) {
      $r = $rechnung[$id];
      $zeile = [
        'feld' => ['#plain_text' => $feld->label()],
        'frucht' => ['#plain_text' => implode(', ', array_unique(array_column($kulturen[$id] ?? [], 'art'))) ?: '–'],
        'flaeche' => [
          '#type' => 'number',
          '#title' => 'Fläche',
          '#title_display' => 'invisible',
          '#step' => 'any',
          '#min' => 0,
          '#size' => 7,
          '#default_value' => $r['flaeche'] !== NULL ? round($r['flaeche'], 4) : NULL,
        ],
      ];
      if ($arbeit !== 'aussaat') {
        $einheit = $menge['einheit'];
        $zeile['je_ha'] = ['#plain_text' => $r['je_ha'] !== NULL ? Aufzeichnung::z($r['je_ha']) . ' ' . $einheit . '/ha' : '–'];
        $zeile['gesamt'] = ['#plain_text' => $r['gesamt'] !== NULL ? Aufzeichnung::z($r['gesamt']) . ' ' . $einheit : '–'];
      }
      if ($arbeit === 'duengung') {
        $zeile['n'] = ['#plain_text' => $r['n_ha'] !== NULL ? Aufzeichnung::z($r['n_ha'], 1) : '–'];
      }
      $form['zeilen'][$id] = $zeile;
    }

    $form['aktionen'] = ['#type' => 'actions'];
    $form['aktionen']['speichern'] = [
      '#type' => 'submit',
      '#value' => count($felder) . ' ' . (count($felder) === 1 ? 'Eintrag' : 'Einträge') . ' speichern',
      '#button_type' => 'primary',
      '#submit' => ['::speichern'],
    ];
    $form['aktionen']['neu_rechnen'] = [
      '#type' => 'submit',
      '#value' => 'Neu berechnen',
      '#submit' => ['::neuRechnen'],
    ];
    $form['aktionen']['zurueck'] = [
      '#type' => 'submit',
      '#value' => 'Zurück',
      '#submit' => ['::zurueck'],
      '#limit_validation_errors' => [],
    ];
    return $form;
  }

  /**
   * Menge je Feld ausrechnen (je ha oder Gesamtmenge nach Fläche aufteilen).
   */
  protected function rechnen(array $e, array $felder, array $flaechen): array {
    $arbeit = $e['arbeit'];
    $menge = $e[$arbeit]['menge'] ?? [];
    $wert = ($menge['wert'] ?? '') === '' ? NULL : (float) $menge['wert'];
    $einheit = $menge['einheit'] ?? '';
    $gesamt_modus = ($menge['bezug'] ?? 'ha') === 'gesamt';
    $n = ($e['duengung']['n_gehalt'] ?? '') === '' ? NULL : (float) $e['duengung']['n_gehalt'];

    $ha = [];
    foreach ($felder as $id => $feld) {
      $ha[$id] = array_key_exists($id, $flaechen) && $flaechen[$id] !== '' && $flaechen[$id] !== NULL
        ? (float) $flaechen[$id]
        : Aufzeichnung::feldgroesse($feld);
    }
    $summe = array_sum(array_filter($ha));
    $ergebnis = [];
    foreach ($felder as $id => $feld) {
      $f = $ha[$id];
      $je_ha = NULL;
      if ($wert !== NULL) {
        $je_ha = $gesamt_modus ? ($summe > 0 ? $wert / $summe : NULL) : $wert;
      }
      $gesamt = ($je_ha !== NULL && $f) ? $je_ha * $f : NULL;
      $n_ha = NULL;
      if ($arbeit === 'duengung' && $n !== NULL && $je_ha !== NULL) {
        $n_ha = $je_ha * ($einheit === 'kg' ? $n / 100 : $n);
      }
      $ergebnis[$id] = ['flaeche' => $f, 'je_ha' => $je_ha, 'gesamt' => $gesamt, 'n_ha' => $n_ha];
    }
    return $ergebnis;
  }

  protected function flaechenAusFormular(FormStateInterface $form_state): array {
    $flaechen = [];
    foreach ((array) $form_state->getValue('zeilen') as $id => $zeile) {
      $flaechen[(string) $id] = $zeile['flaeche'] ?? NULL;
    }
    return $flaechen;
  }

  public function neuRechnen(array &$form, FormStateInterface $form_state): void {
    $form_state->set('flaechen', $this->flaechenAusFormular($form_state));
    $form_state->setRebuild();
  }

  public function zurueck(array &$form, FormStateInterface $form_state): void {
    $form_state->set('schritt', 1);
    $form_state->set('flaechen', NULL);
    $form_state->setRebuild();
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
  }

  /**
   * Speichern: pro Feld ein Eintrag (bei Aussaat zusätzlich die Kultur).
   */
  public function speichern(array &$form, FormStateInterface $form_state): void {
    $e = $form_state->get('eingabe');
    $arbeit = $e['arbeit'];
    $teil = $e[$arbeit];
    [$titel, $logtyp, $kategorie] = self::ARBEITEN[$arbeit];
    $etm = Hof::etm();
    $felder = $etm->getStorage('asset')->loadMultiple($e['felder']);
    $rechnung = $this->rechnen($e, $felder, $this->flaechenAusFormular($form_state));
    $kulturen = Hof::kulturenJeFeld();
    $kategorie_id = Hof::begriff('log_category', $kategorie)->id();
    $menge = $teil['menge'] ?? [];
    $einheit = $menge['einheit'] ?? '';
    $jahr = date('Y', (int) $e['zeit']);

    $material_name = match ($arbeit) {
      'duengung' => trim((string) $teil['duenger']),
      'pflanzenschutz' => trim((string) $teil['mittel']),
      default => NULL,
    };
    $material_id = $material_name ? Hof::begriff('material_type', $material_name)->id() : NULL;

    $anzahl = 0;
    foreach ($felder as $id => $feld) {
      $r = $rechnung[$id];
      $plant_ids = array_column($kulturen[$id] ?? [], 'id');
      $kultur_namen = array_column($kulturen[$id] ?? [], 'name');
      $mengen = [];

      if ($arbeit === 'aussaat') {
        $frucht = trim((string) $teil['frucht']);
        $sorte = trim((string) ($teil['sorte'] ?? ''));
        $pflanze = $etm->getStorage('asset')->create([
          'type' => 'plant',
          'name' => trim($frucht . ' ' . $sorte) . ' – ' . $feld->label() . ' ' . $jahr,
          'plant_type' => [Hof::begriff('plant_type', $frucht)->id()],
          'notes' => $sorte !== '' ? ['value' => 'Sorte: ' . $sorte, 'format' => 'default'] : NULL,
        ]);
        $pflanze->save();
        $plant_ids = [$pflanze->id()];
        $kultur_namen = [$pflanze->label()];
        $name = 'Aussaat ' . $frucht . ' – ' . $feld->label();
        if (($menge['wert'] ?? '') !== '') {
          $mengen[] = $this->mengeAnlegen((float) $menge['wert'], $einheit === 'kg' ? 'kg/ha' : $einheit, 'Saatmenge', 'rate');
        }
      }
      else {
        $name = $titel . ': ' . $material_name . ' – ' . $feld->label();
        if ($r['je_ha'] !== NULL) {
          $mengen[] = $this->mengeAnlegen($r['je_ha'], $einheit . '/ha', $arbeit === 'duengung' ? 'Düngermenge' : 'Aufwandmenge', 'rate', $material_id);
        }
        if ($arbeit === 'pflanzenschutz' && ($teil['wasser'] ?? '') !== '') {
          $mengen[] = $this->mengeAnlegen((float) $teil['wasser'], 'l/ha', 'Wasser', 'rate');
        }
      }
      if ($r['flaeche']) {
        $mengen[] = $this->mengeAnlegen($r['flaeche'], 'ha', 'Behandelte Fläche', 'area');
      }

      // Gleiche Zusatzdaten wie die App (siehe Aufzeichnung::lesen()).
      $daten = [
        'version' => 1,
        'quelle' => 'richterhofOS',
        'art' => $arbeit,
        'feld' => $feld->label(),
        'feld_id' => (string) $feld->uuid(),
        'flaeche_ha' => $r['flaeche'],
        'kultur' => $kultur_namen ? implode(', ', $kultur_namen) : NULL,
      ];
      if ($arbeit !== 'aussaat') {
        $daten += ['mittel' => $material_name, 'menge' => $r['je_ha'], 'einheit' => $einheit . '/ha'];
      }
      if ($arbeit === 'pflanzenschutz') {
        $arten = array_unique(array_column($kulturen[$id] ?? [], 'art'));
        $daten += [
          'verwendung' => 'Freiland',
          'zulassungsnummer' => trim((string) $teil['zulassungsnummer']) ?: NULL,
          'anwender' => trim((string) $teil['anwender']) ?: NULL,
          'eppo' => $this->eppo($arten),
          'bbch' => trim((string) ($teil['bbch'] ?? '')) ?: NULL,
          'wasser_l_ha' => ($teil['wasser'] ?? '') !== '' ? (float) $teil['wasser'] : NULL,
          'grund' => trim((string) ($teil['grund'] ?? '')) ?: NULL,
        ];
      }
      if ($arbeit === 'duengung') {
        $daten += [
          'duengerart' => $teil['duengerart'] ?: NULL,
          'n_gehalt' => ($teil['n_gehalt'] ?? '') !== '' ? (float) $teil['n_gehalt'] : NULL,
          'technik' => $teil['technik'] ?: NULL,
          'einarbeitung' => !empty($teil['einarbeitung']) ? date('c', (int) $teil['einarbeitung']) : NULL,
        ];
      }
      $daten = array_filter($daten, fn($v) => $v !== NULL && $v !== '');

      $werte = [
        'type' => $logtyp,
        'name' => $name,
        'timestamp' => (int) $e['zeit'],
        'status' => 'done',
        'location' => [$feld->id()],
        'asset' => $plant_ids,
        'category' => [$kategorie_id],
        'quantity' => $mengen,
        'data' => json_encode(['richterhof' => $daten], JSON_UNESCAPED_UNICODE),
        'owner' => [$this->currentUser()->id()],
      ];
      if ($arbeit === 'aussaat') {
        $werte['is_movement'] = TRUE;
      }
      if ($e['notiz'] !== '') {
        $werte['notes'] = ['value' => $e['notiz'], 'format' => 'default'];
      }
      if ($e['maschinen']) {
        $werte['equipment'] = $e['maschinen'];
      }
      $definitionen = \Drupal::service('entity_field.manager')->getFieldDefinitions('log', $logtyp);
      $log = $etm->getStorage('log')->create(array_intersect_key($werte, $definitionen + ['type' => TRUE]));
      $log->save();
      $anzahl++;
    }

    $this->messenger()->addStatus($anzahl . ' ' . ($anzahl === 1 ? 'Eintrag' : 'Einträge') . ' „' . $titel . '“ gespeichert.');
    $ziel = match ($arbeit) {
      'duengung' => Url::fromRoute('richterhofos.duengung', [], ['query' => ['jahr' => $jahr]]),
      'pflanzenschutz' => Url::fromRoute('richterhofos.psm', [], ['query' => ['jahr' => $jahr]]),
      default => Url::fromRoute('richterhofos.kulturen'),
    };
    $form_state->setRedirectUrl($ziel);
  }

  /**
   * Menge (Quantity) anlegen und ihre ID zurückgeben.
   */
  protected function mengeAnlegen(float $wert, string $einheit, string $label, string $measure, $material_id = NULL): \Drupal\Core\Entity\EntityInterface {
    $nenner = 10000;
    $werte = [
      'type' => $material_id ? 'material' : 'standard',
      'label' => $label,
      'measure' => $measure,
      'value' => ['numerator' => (int) round($wert * $nenner), 'denominator' => $nenner],
      'units' => [Hof::begriff('unit', $einheit)->id()],
    ];
    if ($material_id) {
      $werte['material_type'] = [$material_id];
    }
    $menge = Hof::etm()->getStorage('quantity')->create($werte);
    $menge->save();
    return $menge;
  }

  /**
   * Zuletzt verwendete Angaben zu einem Mittel/Dünger (aus App oder Web).
   */
  protected function letzte(string $name): array {
    if ($name === '') {
      return [];
    }
    $logs = Hof::etm()->getStorage('log');
    $ids = $logs->getQuery()->accessCheck(TRUE)
      ->condition('type', 'input')
      ->condition('data', '%' . \Drupal::database()->escapeLike('"mittel":' . json_encode($name, JSON_UNESCAPED_UNICODE)) . '%', 'LIKE')
      ->sort('timestamp', 'DESC')
      ->range(0, 5)
      ->execute();
    $ergebnis = [];
    foreach ($logs->loadMultiple($ids) as $log) {
      $d = json_decode((string) $log->get('data')->value, TRUE)['richterhof'] ?? [];
      foreach (['zulassungsnummer', 'n_gehalt', 'duengerart', 'technik'] as $k) {
        if (!isset($ergebnis[$k]) && isset($d[$k]) && $d[$k] !== '') {
          $ergebnis[$k] = $d[$k];
        }
      }
    }
    return $ergebnis;
  }

  /**
   * EPPO-Code zu den Kulturarten (Vorschlag aus bekannter Liste).
   */
  protected function eppo(array $arten): ?string {
    foreach ($arten as $art) {
      $a = mb_strtolower(trim((string) $art));
      if (isset(self::EPPO[$a])) {
        return self::EPPO[$a];
      }
      foreach (self::EPPO as $name => $code) {
        if (str_contains($a, $name)) {
          return $code;
        }
      }
    }
    return NULL;
  }

}
