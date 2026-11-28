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
    'ernte' => ['Ernte', 'harvest', 'Ernte'],
  ];

  /** Ernte-Einheiten => farmOS-Messart. */
  const ERNTE_EINHEITEN = ['Fuhren' => 'count', 'Ballen' => 'count', 't' => 'weight', 'dt' => 'weight', 'kg' => 'weight', 'm³' => 'volume'];
  const ERNTEGUT = ['Grassilage', 'Maissilage', 'Heu', 'Grummet', 'Stroh', 'Weizen', 'Gerste', 'Roggen', 'Triticale', 'Hafer', 'Körnermais', 'Raps', 'Soja', 'Kartoffeln'];

  /** Richtwerte N-Gehalt: Schlüssel => [Name, von, bis, Einheit, Düngerart]. */
  const RICHTWERTE = [
    'rindermist' => ['Rindermist (Festmist)', 4, 7, 't', 'Mist / Kompost'],
    'strohmist' => ['Stallmist mit viel Stroh', 3, 5, 't', 'Mist / Kompost'],
    'rinderguelle' => ['Rindergülle', 3, 6, 'm³', 'Gülle / Jauche'],
    'rinderjauche' => ['Rinderjauche', 1, 3, 'm³', 'Gülle / Jauche'],
  ];

  /** Ladewagen mit Volumen bei mittlerer Pressung (m³). */
  const LADEWAGEN = [
    'ladeprofi2' => ['Ladeprofi II', 35],
    'europrofi5010' => ['Europrofi 5010 Combiline', 50],
    'europrofi5510' => ['Europrofi 5510 Combiline', 55],
  ];

  /** Rundballen: Breite fest 1,23 m. */
  const BALLEN_BREITE = 1.23;

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
      '#prefix' => '<div class="rh-knopfgruppe">',
      '#suffix' => '</div>',
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
      'richtwert' => [
        '#type' => 'select',
        '#title' => 'Wirtschaftsdünger mit Richtwert',
        '#options' => array_map(fn($r) => $r[0] . ' · ca. ' . $r[1] . '–' . $r[2] . ' kg N/' . $r[3], self::RICHTWERTE),
        '#empty_option' => '– anderer Dünger –',
        '#default_value' => $alt['duengung']['richtwert'] ?? NULL,
        '#description' => 'Füllt Dünger, Art, Einheit und N-Gehalt (Mitte der Spanne) aus. Mit Analysewert genauer – dann N-Gehalt überschreiben.',
        '#attributes' => [
          'class' => ['rh-richtwert'],
          'data-richtwerte' => json_encode(self::RICHTWERTE, JSON_UNESCAPED_UNICODE),
        ],
      ],
      'duenger' => $this->material('Dünger', 'z. B. Rindergülle, Mist, NAC 27', $alt['duengung']['duenger'] ?? ''),
      'duengerart' => [
        '#type' => 'radios',
        '#title' => 'Art des Düngers',
        '#options' => array_combine(self::DUENGERARTEN, self::DUENGERARTEN),
        '#default_value' => $alt['duengung']['duengerart'] ?? NULL,
        '#attributes' => ['class' => ['rh-auswahlknoepfe']],
        '#prefix' => '<div class="rh-knopfgruppe">',
        '#suffix' => '</div>',
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

    // ---- Ernte
    $form['ernte'] = [
      '#type' => 'fieldset',
      '#title' => 'Ernte',
      '#tree' => TRUE,
      '#states' => $sichtbar('ernte'),
      'erntegut' => [
        '#type' => 'textfield',
        '#title' => 'Was wurde geerntet?',
        '#description' => 'z. B. Grassilage, Heu, Weizen, Körnermais',
        '#attributes' => ['list' => 'rh-erntegut'],
        '#default_value' => $alt['ernte']['erntegut'] ?? '',
        '#suffix' => \Drupal\Core\Render\Markup::create('<datalist id="rh-erntegut">' . implode('', array_map(fn($g) => '<option value="' . htmlspecialchars($g) . '">', self::ERNTEGUT)) . '</datalist>'),
      ],
      'schnitt' => [
        '#type' => 'select',
        '#title' => 'Schnitt (Grünland)',
        '#options' => ['1' => '1. Schnitt', '2' => '2. Schnitt', '3' => '3. Schnitt', '4' => '4. Schnitt', '5' => '5. Schnitt'],
        '#empty_option' => '– kein Schnitt –',
        '#default_value' => $alt['ernte']['schnitt'] ?? NULL,
      ],
      'einheit' => [
        '#type' => 'radios',
        '#title' => 'Gezählt in',
        '#options' => array_combine(array_keys(self::ERNTE_EINHEITEN), array_keys(self::ERNTE_EINHEITEN)),
        '#default_value' => $alt['ernte']['einheit'] ?? 'Fuhren',
        '#attributes' => ['class' => ['rh-auswahlknoepfe']],
        '#prefix' => '<div class="rh-knopfgruppe">',
        '#suffix' => '</div>',
        '#description' => 'Die Menge je Feld gibst du im nächsten Schritt ein, z. B. 3 Fuhren hier und 7 dort.',
      ],
      'ladewagen' => [
        '#type' => 'container',
        '#attributes' => ['class' => ['rh-mengenzeile']],
        '#states' => ['visible' => [':input[name="ernte[einheit]"]' => ['value' => 'Fuhren']]],
        'modell' => [
          '#type' => 'select',
          '#title' => 'Ladewagen',
          '#options' => array_map(fn($w) => $w[0] . ' · ' . $w[1] . ' m³', self::LADEWAGEN) + ['andere' => 'anderer Wagen'],
          '#empty_option' => '– ohne Volumen –',
          '#default_value' => $alt['ernte']['ladewagen']['modell'] ?? NULL,
          '#description' => 'Volumen bei mittlerer Pressung',
        ],
        'volumen' => [
          '#type' => 'number',
          '#title' => 'Volumen je Fuhre (m³)',
          '#step' => 'any',
          '#min' => 0,
          '#default_value' => $alt['ernte']['ladewagen']['volumen'] ?? NULL,
          '#states' => ['visible' => [':input[name="ernte[ladewagen][modell]"]' => ['value' => 'andere']]],
        ],
      ],
      'ballen' => [
        '#type' => 'container',
        '#states' => ['visible' => [':input[name="ernte[einheit]"]' => ['value' => 'Ballen']]],
        'durchmesser' => [
          '#type' => 'select',
          '#title' => 'Rundballen-Durchmesser',
          '#options' => array_map(fn($d) => $d . ' (' . number_format(M_PI * ((float) str_replace(',', '.', $d) / 2) ** 2 * self::BALLEN_BREITE, 2, ',', '') . ' m³)', ['1.40' => '1,40 m', '1.45' => '1,45 m', '1.50' => '1,50 m', '1.55' => '1,55 m', '1.60' => '1,60 m', '1.65' => '1,65 m', '1.70' => '1,70 m', '1.75' => '1,75 m', '1.80' => '1,80 m', '1.85' => '1,85 m', '1.90' => '1,90 m']),
          '#empty_option' => '– ohne Volumen –',
          '#default_value' => $alt['ernte']['ballen']['durchmesser'] ?? NULL,
          '#description' => 'Breite 1,23 m. Daraus rechnet richterhofOS das Volumen je Ballen.',
        ],
      ],
      'je_einheit' => [
        '#type' => 'container',
        '#attributes' => ['class' => ['rh-mengenzeile']],
        '#states' => ['invisible' => [':input[name="ernte[einheit]"]' => [['value' => 't'], ['value' => 'dt'], ['value' => 'kg']]]],
        'wert' => [
          '#type' => 'number',
          '#title' => 'Gewicht je Fuhre / Ballen / m³ (optional)',
          '#step' => 'any',
          '#min' => 0,
          '#default_value' => $alt['ernte']['je_einheit']['wert'] ?? NULL,
          '#description' => 'Damit rechnet richterhofOS zusätzlich Tonnen und t/ha aus.',
        ],
        'einheit' => [
          '#type' => 'select',
          '#title' => 'Einheit',
          '#options' => ['t' => 't', 'dt' => 'dt', 'kg' => 'kg'],
          '#default_value' => $alt['ernte']['je_einheit']['einheit'] ?? 't',
        ],
      ],
      'feuchte' => [
        '#type' => 'number',
        '#title' => 'Feuchte bzw. TS-Gehalt in % (optional)',
        '#step' => 'any',
        '#min' => 0,
        '#max' => 100,
        '#default_value' => $alt['ernte']['feuchte'] ?? NULL,
      ],
      'beenden' => [
        '#type' => 'checkbox',
        '#title' => 'Kultur damit beenden (z. B. Getreide, Mais) – sie verschwindet aus „Was wächst wo“',
        '#default_value' => $alt['ernte']['beenden'] ?? FALSE,
      ],
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
        '#prefix' => '<div class="rh-knopfgruppe">',
        '#suffix' => '</div>',
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
      if (trim((string) $teil['duenger']) === '' && empty($teil['richtwert'])) {
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
    elseif ($arbeit === 'ernte') {
      if (trim((string) ($teil['erntegut'] ?? '')) === '') {
        $form_state->setErrorByName('ernte][erntegut', 'Bitte angeben, was geerntet wurde.');
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
    if ($arbeit === 'duengung' && !empty($teil['richtwert']) && isset(self::RICHTWERTE[$teil['richtwert']])) {
      [$rw_name, $von, $bis, $rw_einheit, $rw_art] = self::RICHTWERTE[$teil['richtwert']];
      if (trim((string) $teil['duenger']) === '') {
        $teil['duenger'] = $rw_name;
        $eingabe['duengung']['duenger'] = $rw_name;
      }
      if (($teil['n_gehalt'] ?? '') === '') {
        $teil['n_gehalt'] = ($von + $bis) / 2;
        $eingabe['duengung']['n_gehalt'] = $teil['n_gehalt'];
        $eingabe['duengung']['n_quelle'] = 'Richtwert ' . $von . '–' . $bis . ' kg N/' . $rw_einheit;
      }
      if (empty($teil['duengerart'])) {
        $eingabe['duengung']['duengerart'] = $rw_art;
      }
    }
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
      'duengung' => trim((string) $teil['duenger']) . ($teil['duengerart'] ? ' (' . $teil['duengerart'] . ')' : '')
        . (($teil['n_gehalt'] ?? '') !== '' ? ' · ' . Aufzeichnung::z((float) $teil['n_gehalt']) . ' kg N je Einheit' . (!empty($teil['n_quelle']) ? ' (' . $teil['n_quelle'] . ')' : '') : ''),
      'pflanzenschutz' => trim((string) $teil['mittel']) . ' · Zulassung ' . ($teil['zulassungsnummer'] ?: '–'),
      'ernte' => trim((string) $teil['erntegut']) . ($teil['schnitt'] ? ' · ' . $teil['schnitt'] . '. Schnitt' : '')
        . (($v = $this->volumenJeEinheit($teil)) !== NULL && $teil['einheit'] !== 'm³' ? ' · ' . Aufzeichnung::z($v, 2) . ' m³ je ' . ($teil['einheit'] === 'Ballen' ? 'Ballen' : 'Fuhre') : ''),
      default => trim((string) $teil['frucht']) . ($teil['sorte'] ? ' ' . trim((string) $teil['sorte']) : ''),
    };
    $menge = $teil['menge'] ?? [];
    $bezug = ($menge['bezug'] ?? 'ha') === 'gesamt' ? 'gesamt' : 'je ha';
    $form['kopf'] = [
      '#type' => 'inline_template',
      '#template' => '<div class="rh-kontrollkopf"><span class="rh-marke">{{ titel }}</span> <strong>{{ was }}</strong>'
        . '<p>{{ zeit }}{% if menge %} · {{ menge }}{% endif %}</p>'
        . '<p class="rh-hinweis">{{ hinweis }}</p></div>',
      '#context' => [
        'titel' => $titel,
        'was' => $was,
        'zeit' => date('d.m.Y, H:i', (int) $e['zeit']) . ' Uhr',
        'menge' => ($menge['wert'] ?? '') !== '' ? Aufzeichnung::z((float) $menge['wert']) . ' ' . $menge['einheit'] . ' ' . $bezug : '',
        'hinweis' => $arbeit === 'ernte'
          ? 'Trag je Feld die geerntete Menge ein und klick „Neu berechnen“. Felder ohne Menge werden nicht gespeichert.'
          : 'Bitte prüfen. Die Fläche je Feld kannst du ändern, z. B. wenn nur ein Teil gedüngt wurde.',
      ],
    ];

    $kopf = ['Feld', 'Frucht', 'Fläche ha'];
    if ($arbeit === 'ernte') {
      $kopf[] = 'Menge (' . $teil['einheit'] . ')';
      $kopf[] = 'm³';
      $kopf[] = 'm³/ha';
      $kopf[] = 't gesamt';
      $kopf[] = 't/ha';
    }
    elseif ($arbeit !== 'aussaat') {
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
    $mengen = (array) ($form_state->get('mengen') ?? []);
    $rechnung = $this->rechnen($e, $felder, $flaechen, $mengen);
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
      if ($arbeit === 'ernte') {
        $zeile['menge'] = [
          '#type' => 'number',
          '#title' => 'Menge',
          '#title_display' => 'invisible',
          '#step' => 'any',
          '#min' => 0,
          '#size' => 7,
          '#default_value' => $mengen[(string) $id] ?? NULL,
          '#field_suffix' => $teil['einheit'],
        ];
        $zeile['m3'] = ['#plain_text' => $r['m3'] !== NULL ? Aufzeichnung::z($r['m3'], 1) : '–'];
        $zeile['m3_ha'] = ['#plain_text' => $r['m3_ha'] !== NULL ? Aufzeichnung::z($r['m3_ha'], 1) : '–'];
        $zeile['t'] = ['#plain_text' => $r['t'] !== NULL ? Aufzeichnung::z($r['t'], 2) : '–'];
        $zeile['t_ha'] = ['#plain_text' => $r['t_ha'] !== NULL ? Aufzeichnung::z($r['t_ha'], 2) : '–'];
      }
      elseif ($arbeit !== 'aussaat') {
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
      '#value' => $arbeit === 'ernte' ? 'Ernte speichern' : count($felder) . ' ' . (count($felder) === 1 ? 'Eintrag' : 'Einträge') . ' speichern',
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
  protected function rechnen(array $e, array $felder, array $flaechen, array $mengen = []): array {
    $arbeit = $e['arbeit'];
    if ($arbeit === 'ernte') {
      return $this->rechnenErnte($e['ernte'], $felder, $flaechen, $mengen);
    }
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

  /**
   * Ernte: Menge je Feld, umgerechnet in Tonnen und t/ha.
   */
  protected function rechnenErnte(array $teil, array $felder, array $flaechen, array $mengen): array {
    $einheit = $teil['einheit'] ?? 't';
    $faktor = ['t' => 1.0, 'dt' => 0.1, 'kg' => 0.001];
    if (isset($faktor[$einheit])) {
      $je_t = $faktor[$einheit];
    }
    else {
      $w = $teil['je_einheit']['wert'] ?? '';
      $je_t = ($w === '' || $w === NULL) ? NULL : (float) $w * ($faktor[$teil['je_einheit']['einheit'] ?? 't'] ?? 1.0);
    }
    $vol_je = $this->volumenJeEinheit($teil);
    $ergebnis = [];
    foreach ($felder as $id => $feld) {
      $f = array_key_exists((string) $id, $flaechen) && $flaechen[(string) $id] !== '' && $flaechen[(string) $id] !== NULL
        ? (float) $flaechen[(string) $id] : Aufzeichnung::feldgroesse($feld);
      $m = $mengen[(string) $id] ?? '';
      $m = ($m === '' || $m === NULL) ? NULL : (float) $m;
      $t = ($m !== NULL && $je_t !== NULL) ? $m * $je_t : NULL;
      $ergebnis[$id] = [
        'flaeche' => $f,
        'menge' => $m,
        't' => $t,
        't_ha' => ($t !== NULL && $f) ? $t / $f : NULL,
        'm3' => ($m !== NULL && $vol_je !== NULL) ? $m * $vol_je : NULL,
        'm3_ha' => ($m !== NULL && $vol_je !== NULL && $f) ? $m * $vol_je / $f : NULL,
        'je_ha' => NULL,
        'gesamt' => NULL,
        'n_ha' => NULL,
      ];
    }
    return $ergebnis;
  }

  /**
   * Volumen je Zähleinheit in m³: Ladewagen, Rundballen (π·r²·1,23 m) oder m³.
   */
  protected function volumenJeEinheit(array $teil): ?float {
    $einheit = $teil['einheit'] ?? '';
    if ($einheit === 'm³') {
      return 1.0;
    }
    if ($einheit === 'Fuhren') {
      $modell = $teil['ladewagen']['modell'] ?? '';
      if (isset(self::LADEWAGEN[$modell])) {
        return (float) self::LADEWAGEN[$modell][1];
      }
      $v = $teil['ladewagen']['volumen'] ?? '';
      return ($modell === 'andere' && $v !== '' && $v !== NULL) ? (float) $v : NULL;
    }
    if ($einheit === 'Ballen' && !empty($teil['ballen']['durchmesser'])) {
      $d = (float) $teil['ballen']['durchmesser'];
      return M_PI * ($d / 2) ** 2 * self::BALLEN_BREITE;
    }
    return NULL;
  }

  protected function mengenAusFormular(FormStateInterface $form_state): array {
    $mengen = [];
    foreach ((array) $form_state->getValue('zeilen') as $id => $zeile) {
      if (array_key_exists('menge', (array) $zeile)) {
        $mengen[(string) $id] = $zeile['menge'];
      }
    }
    return $mengen;
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
    $form_state->set('mengen', $this->mengenAusFormular($form_state));
    $form_state->setRebuild();
  }

  public function zurueck(array &$form, FormStateInterface $form_state): void {
    $form_state->set('schritt', 1);
    $form_state->set('flaechen', NULL);
    $form_state->set('mengen', NULL);
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
    $rechnung = $this->rechnen($e, $felder, $this->flaechenAusFormular($form_state), $this->mengenAusFormular($form_state));
    if ($arbeit === 'ernte' && !array_filter(array_column($rechnung, 'menge'))) {
      $this->messenger()->addError('Bitte bei mindestens einem Feld eine Menge eintragen.');
      $form_state->set('flaechen', $this->flaechenAusFormular($form_state));
      $form_state->set('mengen', $this->mengenAusFormular($form_state));
      $form_state->setRebuild();
      return;
    }
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
      if ($arbeit === 'ernte' && empty($r['menge'])) {
        continue;
      }
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
      elseif ($arbeit === 'ernte') {
        $gut = trim((string) $teil['erntegut']);
        $schnitt = $teil['schnitt'] ? ' ' . $teil['schnitt'] . '. Schnitt' : '';
        $name = 'Ernte: ' . $gut . $schnitt . ' – ' . $feld->label();
        $ee = $teil['einheit'];
        $mengen[] = $this->mengeAnlegen($r['menge'], $ee, 'Erntemenge', self::ERNTE_EINHEITEN[$ee] ?? 'count');
        if ($r['m3'] !== NULL && $ee !== 'm³') {
          $mengen[] = $this->mengeAnlegen($r['m3'], 'm³', 'Erntemenge (m³)', 'volume');
        }
        if ($r['t'] !== NULL && $ee !== 't') {
          $mengen[] = $this->mengeAnlegen($r['t'], 't', 'Erntemenge (t)', 'weight');
        }
        if (($teil['feuchte'] ?? '') !== '') {
          $mengen[] = $this->mengeAnlegen((float) $teil['feuchte'], '%', 'Feuchte / TS', 'water_content');
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
      if ($arbeit === 'ernte') {
        $daten += [
          'erntegut' => trim((string) $teil['erntegut']),
          'schnitt' => $teil['schnitt'] ?: NULL,
          'menge' => $r['menge'],
          'einheit' => $teil['einheit'],
          'volumen_je_einheit' => $this->volumenJeEinheit($teil),
          'ladewagen' => isset(self::LADEWAGEN[$teil['ladewagen']['modell'] ?? '']) && $teil['einheit'] === 'Fuhren' ? self::LADEWAGEN[$teil['ladewagen']['modell']][0] : NULL,
          'ballen_durchmesser' => $teil['einheit'] === 'Ballen' && !empty($teil['ballen']['durchmesser']) ? (float) $teil['ballen']['durchmesser'] : NULL,
          'm3_gesamt' => $r['m3'],
          'm3_ha' => $r['m3_ha'],
          't_gesamt' => $r['t'],
          't_ha' => $r['t_ha'],
          'feuchte' => ($teil['feuchte'] ?? '') !== '' ? (float) $teil['feuchte'] : NULL,
        ];
      }
      elseif ($arbeit !== 'aussaat') {
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
          'n_quelle' => $teil['n_quelle'] ?? NULL,
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

      // Kultur nach der Ernte beenden (archivieren).
      if ($arbeit === 'ernte' && !empty($teil['beenden'])) {
        foreach ($etm->getStorage('asset')->loadMultiple($plant_ids) as $pflanze) {
          if ($pflanze->hasField('archived')) {
            $pflanze->set('archived', TRUE);
          }
          elseif ($pflanze->hasField('status')) {
            $pflanze->set('status', 'archived');
          }
          $pflanze->save();
        }
      }
    }

    $this->messenger()->addStatus($anzahl . ' ' . ($anzahl === 1 ? 'Eintrag' : 'Einträge') . ' „' . $titel . '“ gespeichert.');
    $ziel = match ($arbeit) {
      'duengung' => Url::fromRoute('richterhofos.duengung', [], ['query' => ['jahr' => $jahr]]),
      'pflanzenschutz' => Url::fromRoute('richterhofos.psm', [], ['query' => ['jahr' => $jahr]]),
      'ernte' => Url::fromRoute('richterhofos.ernte', [], ['query' => ['jahr' => $jahr]]),
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
