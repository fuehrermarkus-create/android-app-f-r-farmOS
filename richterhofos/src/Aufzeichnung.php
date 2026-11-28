<?php

declare(strict_types=1);

namespace Drupal\richterhofos;

use Drupal\Core\Entity\EntityInterface;

/**
 * Liest Pflanzenschutz- und Düngungs-Einträge eines Jahres für die Aufzeichnungen.
 *
 * Quelle sind die Logs mit Kategorie „Pflanzenschutz“ bzw. „Düngung“. Die App
 * (ab 1.11) legt die Pflichtangaben zusätzlich maschinenlesbar im Feld „data“
 * ab ({"richterhof": {...}}). Ältere Einträge werden so gut wie möglich aus
 * Titel, Mengen, Ort und Kulturen gelesen; was fehlt, bleibt leer.
 */
final class Aufzeichnung {

  const PSM = 'Pflanzenschutz';
  const DUENGUNG = 'Düngung';
  const ERNTE = 'Ernte';

  /**
   * Alle erledigten Einträge einer Kategorie in einem Jahr, nach Zeit sortiert.
   *
   * @return array[]
   */
  public static function eintraege(string $kategorie, int $jahr): array {
    $etm = \Drupal::entityTypeManager();
    $tids = $etm->getStorage('taxonomy_term')->getQuery()->accessCheck(TRUE)
      ->condition('vid', 'log_category')
      ->condition('name', $kategorie)
      ->execute();
    if (!$tids) {
      return [];
    }
    $von = mktime(0, 0, 0, 1, 1, $jahr);
    $bis = mktime(0, 0, 0, 1, 1, $jahr + 1);
    $ids = $etm->getStorage('log')->getQuery()->accessCheck(TRUE)
      ->condition('category', array_values($tids), 'IN')
      ->condition('timestamp', $von, '>=')
      ->condition('timestamp', $bis, '<')
      ->condition('status', 'done')
      ->sort('timestamp', 'ASC')
      ->execute();
    $zeilen = [];
    foreach ($etm->getStorage('log')->loadMultiple($ids) as $log) {
      $zeilen[] = self::lesen($log, $kategorie);
    }
    return $zeilen;
  }

  /**
   * Ein Log in eine flache Zeile mit allen Angaben übersetzen.
   */
  public static function lesen(EntityInterface $log, string $kategorie): array {
    $daten = [];
    if ($log->hasField('data') && ($roh = (string) $log->get('data')->value) !== '') {
      $json = json_decode($roh, TRUE);
      if (is_array($json) && is_array($json['richterhof'] ?? NULL)) {
        $daten = $json['richterhof'];
      }
    }

    // Mengen: Aufwand/Dünger, behandelte Fläche, Wasser; Material an der Menge.
    $menge = $daten['menge'] ?? NULL;
    $einheit = $daten['einheit'] ?? NULL;
    $flaeche = $daten['flaeche_ha'] ?? NULL;
    $wasser = $daten['wasser_l_ha'] ?? NULL;
    $material = NULL;
    if ($log->hasField('quantity')) {
      foreach ($log->get('quantity')->referencedEntities() as $q) {
        $wert = self::zahl($q);
        $label = mb_strtolower((string) ($q->hasField('label') ? $q->get('label')->value : ''));
        $unit = ($q->hasField('units') && $q->get('units')->entity) ? $q->get('units')->entity->label() : NULL;
        if ($q->hasField('material_type') && ($m = $q->get('material_type')->entity)) {
          $material = $m->label();
        }
        if (str_contains($label, 'fläche')) {
          $flaeche ??= $wert;
        }
        elseif (str_contains($label, 'wasser')) {
          $wasser ??= $wert;
        }
        elseif ($menge === NULL && $wert !== NULL) {
          $menge = $wert;
          $einheit = $unit ?? $einheit;
        }
      }
    }

    // Feld, Feldstücknummer und Feldgröße.
    $feld = NULL;
    if ($log->hasField('location')) {
      $feld = $log->get('location')->referencedEntities()[0] ?? NULL;
    }
    $feldstueck = NULL;
    if ($feld && $feld->hasField('id_tag')) {
      foreach ($feld->get('id_tag') as $tag) {
        if (!empty($tag->id)) {
          $feldstueck = $tag->id;
          break;
        }
      }
    }
    $groesse = $feld ? self::feldgroesse($feld) : NULL;
    $flaeche ??= $groesse;

    // Kulturen: aus den Daten, sonst Kulturen am Log, sonst was gerade auf dem Feld steht.
    $kultur = $daten['kultur'] ?? NULL;
    if ($kultur === NULL && $log->hasField('asset')) {
      $namen = [];
      foreach ($log->get('asset')->referencedEntities() as $a) {
        if ($a->bundle() === 'plant') {
          $namen[] = $a->label();
        }
      }
      $kultur = $namen ? implode(', ', $namen) : NULL;
    }
    if ($kultur === NULL && $feld) {
      static $kulturen = NULL;
      $kulturen ??= Hof::kulturenJeFeld();
      $hier = $kulturen[$feld->id()] ?? [];
      $kultur = $hier ? implode(', ', array_unique(array_column($hier, 'name'))) : NULL;
    }

    // Mittel bzw. Dünger.
    $mittel = $daten['mittel'] ?? $material;
    if ($mittel === NULL && preg_match('/^[^:]+:\s*(.+?)(?:\s+–\s+.*)?$/u', (string) $log->label(), $m)) {
      $mittel = $m[1];
    }

    // Anwender: aus den Daten, sonst Besitzer, sonst wer den Eintrag angelegt hat.
    $anwender = $daten['anwender'] ?? NULL;
    if ($anwender === NULL && $log->hasField('owner')) {
      $namen = array_map(fn($u) => $u->getDisplayName(), $log->get('owner')->referencedEntities());
      $anwender = $namen ? implode(', ', $namen) : NULL;
    }
    if ($anwender === NULL && method_exists($log, 'getRevisionUser') && $log->getRevisionUser()) {
      $anwender = $log->getRevisionUser()->getDisplayName();
    }

    // Menge je ha und gesamt.
    $je_ha = NULL;
    $gesamt = NULL;
    $einheit_basis = $einheit ? preg_replace('#/ha$#', '', $einheit) : NULL;
    if ($menge !== NULL) {
      if ($einheit && str_ends_with($einheit, '/ha')) {
        $je_ha = $menge;
        $gesamt = $flaeche ? $menge * $flaeche : NULL;
      }
      else {
        $gesamt = $menge;
        $je_ha = $flaeche ? $menge / $flaeche : NULL;
      }
    }

    // Stickstoff (Düngung): N-Gehalt je Einheit, bei kg in Prozent.
    $n_gehalt = $daten['n_gehalt'] ?? NULL;
    $n_gesamt = NULL;
    if ($n_gehalt !== NULL && $gesamt !== NULL) {
      $je_einheit = ($einheit_basis && str_starts_with($einheit_basis, 'kg')) ? $n_gehalt / 100 : $n_gehalt;
      $n_gesamt = $gesamt * $je_einheit;
    }

    $ts = (int) $log->get('timestamp')->value;
    $einarbeitung = isset($daten['einarbeitung']) ? strtotime((string) $daten['einarbeitung']) : NULL;

    return [
      'log' => $log,
      'url' => Hof::link($log),
      'zeit' => $ts,
      'datum' => date('d.m.Y', $ts),
      'uhrzeit' => date('H:i', $ts),
      'feld' => $feld ? $feld->label() : ($daten['feld'] ?? NULL),
      'feld_id' => $feld ? $feld->id() : NULL,
      'feldstueck' => $feldstueck,
      'feldgroesse' => $groesse,
      'flaeche' => $flaeche,
      'kultur' => $kultur,
      'eppo' => $daten['eppo'] ?? NULL,
      'bbch' => $daten['bbch'] ?? NULL,
      'mittel' => $mittel,
      'zulassungsnummer' => $daten['zulassungsnummer'] ?? NULL,
      'menge' => $menge,
      'einheit' => $einheit,
      'einheit_basis' => $einheit_basis,
      'je_ha' => $je_ha,
      'gesamt' => $gesamt,
      'wasser' => $wasser,
      'grund' => $daten['grund'] ?? NULL,
      'verwendung' => $daten['verwendung'] ?? 'Freiland',
      'anwender' => $anwender,
      'duengerart' => $daten['duengerart'] ?? NULL,
      'n_gehalt' => $n_gehalt,
      'n_gesamt' => $n_gesamt,
      'n_ha' => ($n_gesamt !== NULL && $flaeche) ? $n_gesamt / $flaeche : NULL,
      'technik' => $daten['technik'] ?? NULL,
      'einarbeitung' => $einarbeitung ? date('d.m.Y H:i', $einarbeitung) : NULL,
      'erntegut' => $daten['erntegut'] ?? $mittel,
      'schnitt' => $daten['schnitt'] ?? NULL,
      't_gesamt' => $daten['t_gesamt'] ?? (($einheit === 't') ? $menge : NULL),
      't_ha' => $daten['t_ha'] ?? NULL,
      'aus_app' => (bool) $daten,
    ];
  }

  /**
   * Zahlenwert einer Menge (Bruchfeld) oder NULL.
   */
  public static function zahl(EntityInterface $menge): ?float {
    if (!$menge->hasField('value') || $menge->get('value')->isEmpty()) {
      return NULL;
    }
    $v = $menge->get('value')->first();
    $zaehler = $v->numerator ?? NULL;
    $nenner = $v->denominator ?? NULL;
    if ($zaehler === NULL || !$nenner) {
      return NULL;
    }
    return (float) $zaehler / (float) $nenner;
  }

  /**
   * Feldgröße in ha: aus farmOS (m²) oder aus den Notizen („1,6821 ha“).
   */
  public static function feldgroesse(EntityInterface $feld): ?float {
    if ($feld->hasField('area') && is_numeric($feld->get('area')->value ?? NULL)) {
      return ((float) $feld->get('area')->value) / 10000;
    }
    $notiz = $feld->hasField('notes') ? (string) $feld->get('notes')->value : '';
    if (preg_match('/([0-9]+[.,][0-9]+|[0-9]+)\s*ha/u', $notiz, $m)) {
      return (float) str_replace(',', '.', $m[1]);
    }
    return NULL;
  }

  /**
   * Zahl im österreichischen Format.
   */
  public static function z(?float $wert, int $stellen = 2): string {
    return $wert === NULL ? '' : number_format($wert, $stellen, ',', '.');
  }

  /**
   * Jahre, für die eine Auswahl angeboten wird (aktuelles und vier davor).
   */
  public static function jahre(): array {
    $jetzt = (int) date('Y');
    return range($jetzt, $jetzt - 4);
  }

}
