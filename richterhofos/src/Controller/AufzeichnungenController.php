<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Controller;

use Drupal\Core\Controller\ControllerBase;
use Drupal\Core\Url;
use Drupal\richterhofos\Aufzeichnung;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Jahresausgaben: Pflanzenschutz-Aufzeichnung und Düngeaufzeichnung.
 */
class AufzeichnungenController extends ControllerBase {

  protected function jahr(Request $request): int {
    $jahr = (int) $request->query->get('jahr', date('Y'));
    return ($jahr >= 2000 && $jahr <= 2100) ? $jahr : (int) date('Y');
  }

  /**
   * /aufzeichnungen – Auswahl mit den zwei Knöpfen.
   */
  public function uebersicht(Request $request): array {
    $jahr = $this->jahr($request);
    $ausgaben = [];
    foreach ([
      ['richterhofos.psm', 'Pflanzenschutz-Aufzeichnung', 'Alle Pflanzenschutz-Anwendungen des Jahres mit Mittel, Zulassungsnummer, Aufwand, Fläche, Kultur und Anwender.', Aufzeichnung::PSM],
      ['richterhofos.duengung', 'Düngeaufzeichnung', 'Alle Düngungen des Jahres je Feld mit Dünger, Menge und Stickstoff, dazu die Summen für den Betrieb.', Aufzeichnung::DUENGUNG],
    ] as [$route, $titel, $text, $kat]) {
      $ausgaben[] = [
        'titel' => $titel,
        'text' => $text,
        'anzahl' => count(Aufzeichnung::eintraege($kat, $jahr)),
        'url' => Url::fromRoute($route, [], ['query' => ['jahr' => $jahr]])->toString(),
      ];
    }
    return [
      '#attached' => ['library' => ['richterhofos/aufzeichnungen']],
      '#cache' => ['tags' => ['log_list'], 'contexts' => ['url.query_args:jahr', 'user']],
      'jahre' => $this->jahrWahl('richterhofos.aufzeichnungen', $jahr),
      'knoepfe' => [
        '#type' => 'inline_template',
        '#template' => '<div class="rh-ausgaben">{% for a in ausgaben %}<a class="rh-ausgabe" href="{{ a.url }}">'
          . '<span class="rh-ausgabe__titel">{{ a.titel }} {{ jahr }}</span>'
          . '<span class="rh-ausgabe__text">{{ a.text }}</span>'
          . '<span class="rh-marke">{{ a.anzahl }} Einträge</span></a>{% endfor %}</div>',
        '#context' => ['ausgaben' => $ausgaben, 'jahr' => $jahr],
      ],
      'hinweis' => [
        '#markup' => '<p class="rh-hinweis">Die Angaben kommen aus den Einträgen „Pflanzenschutz“ und „Düngung“ in der App. '
        . 'Jede Ausgabe lässt sich drucken (oder als PDF speichern) und als CSV-Datei für Excel herunterladen. '
        . 'Aufbewahren: Pflanzenschutz mindestens 3 Jahre, Düngung 7 Jahre.</p>',
      ],
    ];
  }

  /**
   * Jahresauswahl als Chips.
   */
  protected function jahrWahl(string $route, int $jahr, array $extra = []): array {
    $links = [];
    foreach (Aufzeichnung::jahre() as $j) {
      $links[] = [
        'jahr' => $j,
        'url' => Url::fromRoute($route, [], ['query' => ['jahr' => $j]])->toString(),
      ];
    }
    return [
      '#type' => 'inline_template',
      '#template' => '<nav class="rh-chips" aria-label="Jahr">{% for l in links %}'
        . '<a class="rh-chip{{ l.jahr == jahr ? \' is-aktiv\' }}" href="{{ l.url }}">{{ l.jahr }}</a>{% endfor %}'
        . '{% for e in extra %}<a class="rh-chip rh-chip--aktion" href="{{ e.url }}">{{ e.titel }}</a>{% endfor %}'
        . '<button type="button" class="rh-chip rh-drucken">Drucken / PDF</button></nav>',
      '#context' => ['links' => $links, 'jahr' => $jahr, 'extra' => $extra],
    ];
  }

  /**
   * Leere Pflichtangabe sichtbar machen.
   */
  protected static function f($wert): array {
    if ($wert === NULL || $wert === '') {
      return ['data' => ['#markup' => '<span class="rh-fehlt">fehlt</span>']];
    }
    return ['data' => ['#plain_text' => (string) $wert]];
  }

  protected static function link(array $z, string $text): array {
    return $z['url']
      ? ['data' => ['#type' => 'link', '#title' => $text, '#url' => Url::fromUserInput($z['url'])]]
      : ['data' => ['#plain_text' => $text]];
  }

  // ===================================================================== Pflanzenschutz

  public function psmTitel(Request $request): string {
    return 'Pflanzenschutz-Aufzeichnung ' . $this->jahr($request);
  }

  public function psm(Request $request): array {
    $jahr = $this->jahr($request);
    $eintraege = Aufzeichnung::eintraege(Aufzeichnung::PSM, $jahr);
    $betrieb = $this->config('system.site')->get('name');

    $zeilen = [];
    $fehlen = 0;
    $summen = [];
    foreach ($eintraege as $z) {
      $pflicht = [$z['mittel'], $z['zulassungsnummer'], $z['je_ha'], $z['flaeche'], $z['kultur'], $z['anwender']];
      if (in_array(NULL, $pflicht, TRUE) || in_array('', $pflicht, TRUE)) {
        $fehlen++;
      }
      $einheit = $z['einheit_basis'] ?: '';
      if ($z['mittel'] && $z['gesamt'] !== NULL) {
        $k = $z['mittel'] . '|' . $einheit;
        $summen[$k] = ($summen[$k] ?? 0) + $z['gesamt'];
      }
      $zeilen[] = [
        self::link($z, $z['datum']),
        $z['uhrzeit'],
        self::f($z['anwender']),
        self::f($z['feldstueck']),
        self::f($z['feld']),
        self::f(Aufzeichnung::z($z['flaeche'], 4) ?: NULL),
        self::f($z['kultur']),
        $z['eppo'] ?? '',
        $z['bbch'] ?? '',
        self::f($z['mittel']),
        self::f($z['zulassungsnummer']),
        self::f($z['je_ha'] !== NULL ? Aufzeichnung::z($z['je_ha'], 3) . ' ' . $einheit . '/ha' : NULL),
        $z['gesamt'] !== NULL ? Aufzeichnung::z($z['gesamt'], 2) . ' ' . $einheit : '',
        $z['wasser'] !== NULL ? Aufzeichnung::z($z['wasser'], 0) : '',
        $z['grund'] ?? '',
      ];
    }

    $summen_zeilen = [];
    ksort($summen, SORT_NATURAL | SORT_FLAG_CASE);
    foreach ($summen as $k => $menge) {
      [$mittel, $einheit] = explode('|', $k);
      $summen_zeilen[] = [$mittel, Aufzeichnung::z($menge, 2) . ' ' . $einheit];
    }

    return [
      '#attached' => ['library' => ['richterhofos/aufzeichnungen']],
      '#cache' => ['tags' => ['log_list', 'asset_list'], 'contexts' => ['url.query_args:jahr', 'user']],
      'jahre' => $this->jahrWahl('richterhofos.psm', $jahr, [[
        'titel' => 'CSV herunterladen',
        'url' => Url::fromRoute('richterhofos.psm_csv', [], ['query' => ['jahr' => $jahr]])->toString(),
      ]]),
      'kopf' => [
        '#type' => 'inline_template',
        '#template' => '<div class="rh-druckkopf"><strong>{{ betrieb }}</strong> · Aufzeichnung über die Verwendung von Pflanzenschutzmitteln {{ jahr }} '
          . '(Art. 67 VO (EG) 1107/2009, DVO (EU) 2023/564) · Art der Verwendung: Freiland · erstellt am {{ heute }}</div>'
          . '{% if fehlen %}<p class="rh-warnung">{{ fehlen }} von {{ gesamt }} Einträgen fehlen Pflichtangaben (rot markiert). '
          . 'Datum anklicken öffnet den Eintrag. Neue Einträge in der App ab Version 1.11 fragen alle Angaben ab.</p>{% endif %}',
        '#context' => [
          'betrieb' => $betrieb,
          'jahr' => $jahr,
          'heute' => date('d.m.Y'),
          'fehlen' => $fehlen,
          'gesamt' => count($eintraege),
        ],
      ],
      'tabelle' => [
        '#type' => 'table',
        '#header' => ['Datum', 'Uhrzeit', 'Anwender', 'Feldstück-Nr.', 'Feld / Schlag', 'Fläche ha', 'Kultur', 'EPPO', 'BBCH',
          'Pflanzenschutzmittel', 'Zulassungs-Nr.', 'Aufwand je ha', 'Menge gesamt', 'Wasser l/ha', 'Grund'],
        '#rows' => $zeilen,
        '#empty' => 'Keine Pflanzenschutz-Einträge in ' . $jahr . '.',
        '#attributes' => ['class' => ['rh-tabelle', 'rh-aufzeichnung']],
      ],
      'summen_titel' => ['#markup' => '<h3 class="rh-abschnitt">Verbrauch je Mittel</h3>'],
      'summen' => [
        '#type' => 'table',
        '#header' => ['Pflanzenschutzmittel', 'Menge gesamt'],
        '#rows' => $summen_zeilen,
        '#empty' => '–',
        '#attributes' => ['class' => ['rh-tabelle', 'rh-summen']],
      ],
    ];
  }

  public function psmCsv(Request $request): Response {
    $jahr = $this->jahr($request);
    $kopf = ['Datum', 'Uhrzeit', 'Anwender', 'Art der Verwendung', 'Feldstueck_Nr', 'Feld', 'Flaeche_ha', 'Kultur', 'EPPO', 'BBCH',
      'Pflanzenschutzmittel', 'Zulassungsnummer', 'Aufwand_je_ha', 'Einheit_je_ha', 'Menge_gesamt', 'Einheit', 'Wasser_l_ha', 'Grund'];
    $zeilen = [];
    foreach (Aufzeichnung::eintraege(Aufzeichnung::PSM, $jahr) as $z) {
      $e = $z['einheit_basis'] ?: '';
      $zeilen[] = [
        date('Y-m-d', $z['zeit']), $z['uhrzeit'], $z['anwender'], $z['verwendung'], $z['feldstueck'], $z['feld'],
        Aufzeichnung::z($z['flaeche'], 4), $z['kultur'], $z['eppo'], $z['bbch'], $z['mittel'], $z['zulassungsnummer'],
        Aufzeichnung::z($z['je_ha'], 3), $e ? $e . '/ha' : '', Aufzeichnung::z($z['gesamt'], 3), $e,
        Aufzeichnung::z($z['wasser'], 0), $z['grund'],
      ];
    }
    return $this->csv("pflanzenschutz-$jahr.csv", $kopf, $zeilen);
  }

  // ===================================================================== Düngung

  public function duengungTitel(Request $request): string {
    return 'Düngeaufzeichnung ' . $this->jahr($request);
  }

  public function duengung(Request $request): array {
    $jahr = $this->jahr($request);
    $eintraege = Aufzeichnung::eintraege(Aufzeichnung::DUENGUNG, $jahr);
    $betrieb = $this->config('system.site')->get('name');

    // Nach Feld gruppieren.
    $felder = [];
    foreach ($eintraege as $z) {
      $k = $z['feld_id'] ?? ('x' . ($z['feld'] ?? ''));
      $felder[$k]['feld'] ??= $z['feld'];
      $felder[$k]['feldstueck'] ??= $z['feldstueck'];
      $felder[$k]['groesse'] ??= $z['feldgroesse'];
      $felder[$k]['kulturen'][$z['kultur'] ?? ''] = TRUE;
      $felder[$k]['eintraege'][] = $z;
    }
    uasort($felder, fn($a, $b) => strnatcasecmp((string) $a['feld'], (string) $b['feld']));

    $fehlen = 0;
    $je_art = [];
    $geduengt = 0.0;
    $bloecke = [];
    foreach ($felder as $f) {
      $zeilen = [];
      $n_summe = 0.0;
      $n_bekannt = TRUE;
      foreach ($f['eintraege'] as $z) {
        if ($z['n_gesamt'] === NULL) {
          $n_bekannt = FALSE;
          $fehlen++;
        }
        else {
          $n_summe += $z['n_gesamt'];
          $art = $z['duengerart'] ?: 'ohne Angabe';
          $je_art[$art] = ($je_art[$art] ?? 0) + $z['n_gesamt'];
        }
        $e = $z['einheit_basis'] ?: '';
        $zeilen[] = [
          self::link($z, $z['datum'] . ' ' . $z['uhrzeit']),
          self::f($z['mittel']),
          $z['duengerart'] ?? '',
          self::f($z['menge'] !== NULL ? Aufzeichnung::z($z['menge'], 2) . ' ' . ($z['einheit'] ?? '') : NULL),
          $z['je_ha'] !== NULL ? Aufzeichnung::z($z['je_ha'], 2) . ' ' . $e . '/ha' : '',
          self::f(Aufzeichnung::z($z['flaeche'], 4) ?: NULL),
          self::f($z['n_gehalt'] !== NULL ? Aufzeichnung::z($z['n_gehalt'], 2) . (str_starts_with($e, 'kg') ? ' %' : ' kg/' . $e) : NULL),
          self::f(Aufzeichnung::z($z['n_ha'], 1) ?: NULL),
          $z['technik'] ?? '',
          $z['einarbeitung'] ?? '',
        ];
      }
      $groesse = $f['groesse'];
      if ($groesse) {
        $geduengt += $groesse;
      }
      $bloecke[] = [
        'feld' => $f['feld'] ?: 'ohne Feld',
        'feldstueck' => $f['feldstueck'],
        'groesse' => Aufzeichnung::z($groesse, 4),
        'kultur' => implode(', ', array_filter(array_keys($f['kulturen']))),
        'n_summe' => Aufzeichnung::z($n_summe, 1),
        'n_ha' => ($groesse && $n_summe) ? Aufzeichnung::z($n_summe / $groesse, 1) : '',
        'vollstaendig' => $n_bekannt,
        'tabelle' => [
          '#type' => 'table',
          '#header' => ['Datum / Uhrzeit', 'Dünger', 'Art', 'Menge', 'Menge je ha', 'Fläche ha', 'N-Gehalt', 'kg N/ha', 'Technik', 'Einarbeitung'],
          '#rows' => $zeilen,
          '#attributes' => ['class' => ['rh-tabelle', 'rh-aufzeichnung']],
        ],
      ];
    }

    ksort($je_art, SORT_NATURAL | SORT_FLAG_CASE);
    $art_zeilen = [];
    foreach ($je_art as $art => $kg) {
      $art_zeilen[] = [$art, Aufzeichnung::z($kg, 1)];
    }
    $art_zeilen[] = [['data' => ['#markup' => '<strong>Summe</strong>']], ['data' => ['#markup' => '<strong>' . Aufzeichnung::z(array_sum($je_art), 1) . '</strong>']]];

    // Gesamtfläche aller aktiven Felder.
    $gesamtflaeche = 0.0;
    foreach (\Drupal\richterhofos\Hof::felder() as $feld) {
      $gesamtflaeche += Aufzeichnung::feldgroesse($feld) ?? 0;
    }

    $build = [
      '#attached' => ['library' => ['richterhofos/aufzeichnungen']],
      '#cache' => ['tags' => ['log_list', 'asset_list'], 'contexts' => ['url.query_args:jahr', 'user']],
      'jahre' => $this->jahrWahl('richterhofos.duengung', $jahr, [[
        'titel' => 'CSV herunterladen',
        'url' => Url::fromRoute('richterhofos.duengung_csv', [], ['query' => ['jahr' => $jahr]])->toString(),
      ]]),
      'kopf' => [
        '#type' => 'inline_template',
        '#template' => '<div class="rh-druckkopf"><strong>{{ betrieb }}</strong> · Düngeaufzeichnung {{ jahr }} '
          . '(schlagbezogen, Nitrat-Aktionsprogramm-Verordnung) · erstellt am {{ heute }}</div>'
          . '{% if fehlen %}<p class="rh-warnung">Bei {{ fehlen }} Düngungen fehlt der N-Gehalt oder die Menge, sie zählen nicht in die Summen. '
          . 'Datum anklicken öffnet den Eintrag.</p>{% endif %}',
        '#context' => ['betrieb' => $betrieb, 'jahr' => $jahr, 'heute' => date('d.m.Y'), 'fehlen' => $fehlen],
      ],
    ];
    foreach ($bloecke as $i => $b) {
      $build['feld_' . $i] = [
        '#type' => 'container',
        '#attributes' => ['class' => ['rh-feldblock']],
        'kopf' => [
          '#type' => 'inline_template',
          '#template' => '<h3 class="rh-feldblock__titel">{{ b.feld }}{% if b.feldstueck %} <small>Feldstück {{ b.feldstueck }}</small>{% endif %}</h3>'
            . '<p class="rh-feldblock__info">{% if b.groesse %}{{ b.groesse }} ha · {% endif %}{% if b.kultur %}Kultur: {{ b.kultur }} · {% endif %}'
            . 'N gesamt: <strong>{{ b.n_summe }} kg</strong>{% if b.n_ha %} = <strong>{{ b.n_ha }} kg N/ha</strong>{% endif %}'
            . '{% if not b.vollstaendig %} <span class="rh-fehlt">unvollständig</span>{% endif %}</p>',
          '#context' => ['b' => $b],
        ],
        'tabelle' => $b['tabelle'],
      ];
    }
    if (!$bloecke) {
      $build['leer'] = ['#markup' => '<p class="rh-leer">Keine Düngungen in ' . $jahr . '.</p>'];
    }
    $build['betrieb_titel'] = ['#markup' => '<h3 class="rh-abschnitt">Betrieb gesamt ' . $jahr . '</h3>'];
    $build['betrieb'] = [
      '#type' => 'table',
      '#header' => ['Stickstoff nach Düngerart', 'kg N'],
      '#rows' => $art_zeilen,
      '#attributes' => ['class' => ['rh-tabelle', 'rh-summen']],
    ];
    $build['flaechen'] = [
      '#type' => 'table',
      '#header' => ['Flächen', 'ha'],
      '#rows' => [
        ['Fläche aller aktiven Felder', Aufzeichnung::z($gesamtflaeche, 2)],
        ['davon mit Stickstoff gedüngt', Aufzeichnung::z($geduengt, 2)],
      ],
      '#attributes' => ['class' => ['rh-tabelle', 'rh-summen']],
    ];
    $build['hinweis'] = [
      '#markup' => '<p class="rh-hinweis">Nicht enthalten: Stickstoffanfall aus der Tierhaltung, Ab- und Übergabe von '
      . 'Wirtschaftsdünger, Bedarf je Kultur nach Ertragslage. Diese Werte für die betriebsbezogene Aufzeichnung '
      . 'z. B. mit dem LK-Düngerechner ergänzen.</p>',
    ];
    return $build;
  }

  public function duengungCsv(Request $request): Response {
    $jahr = $this->jahr($request);
    $kopf = ['Datum', 'Uhrzeit', 'Feldstueck_Nr', 'Feld', 'Feldgroesse_ha', 'Kultur', 'Duenger', 'Duengerart', 'Menge', 'Einheit',
      'Menge_je_ha', 'Flaeche_ha', 'N_Gehalt', 'kg_N_gesamt', 'kg_N_je_ha', 'Technik', 'Einarbeitung'];
    $zeilen = [];
    foreach (Aufzeichnung::eintraege(Aufzeichnung::DUENGUNG, $jahr) as $z) {
      $zeilen[] = [
        date('Y-m-d', $z['zeit']), $z['uhrzeit'], $z['feldstueck'], $z['feld'], Aufzeichnung::z($z['feldgroesse'], 4),
        $z['kultur'], $z['mittel'], $z['duengerart'], Aufzeichnung::z($z['menge'], 3), $z['einheit'],
        Aufzeichnung::z($z['je_ha'], 3), Aufzeichnung::z($z['flaeche'], 4), Aufzeichnung::z($z['n_gehalt'], 3),
        Aufzeichnung::z($z['n_gesamt'], 2), Aufzeichnung::z($z['n_ha'], 2), $z['technik'], $z['einarbeitung'],
      ];
    }
    return $this->csv("duengung-$jahr.csv", $kopf, $zeilen);
  }

  /**
   * CSV für Excel (Semikolon, UTF-8 mit BOM).
   */
  protected function csv(string $datei, array $kopf, array $zeilen): Response {
    $h = fopen('php://temp', 'r+');
    fwrite($h, "\xEF\xBB\xBF");
    fputcsv($h, $kopf, ';', '"', '');
    foreach ($zeilen as $z) {
      fputcsv($h, array_map(fn($v) => (string) ($v ?? ''), $z), ';', '"', '');
    }
    rewind($h);
    $inhalt = stream_get_contents($h);
    fclose($h);
    $antwort = new Response($inhalt);
    $antwort->headers->set('Content-Type', 'text/csv; charset=utf-8');
    $antwort->headers->set('Content-Disposition', 'attachment; filename="' . $datei . '"');
    $antwort->setPrivate();
    return $antwort;
  }

}
