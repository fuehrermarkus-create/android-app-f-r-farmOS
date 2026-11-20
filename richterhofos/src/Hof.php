<?php

declare(strict_types=1);

namespace Drupal\richterhofos;

use Drupal\Core\Entity\EntityInterface;
use Drupal\Core\Entity\Query\QueryInterface;
use Drupal\taxonomy\TermInterface;

/**
 * Gemeinsame Helfer für Listen und Kulturen.
 *
 * Listen werden genau so gespeichert wie von der App „farmOS Hof“:
 * - Kategorie (log_category) „Liste: <Name>“, Beschreibung mit „Arbeit: <SCHLÜSSEL>“ und „Notiz: …“
 * - pro Feld ein Eintrag „<Name> – <Feld>“ mit dieser Kategorie, Status offen/erledigt
 * - abgeschlossene Listen heißen „Archiv: <Name>“
 */
final class Hof {

  const LISTE = 'Liste: ';
  const ARCHIV = 'Archiv: ';
  const BLUEH = 'bluehflaeche';

  /**
   * Arbeiten für Listen (gleiche Schlüssel wie in der App).
   *
   * Schlüssel => [Bezeichnung, Eintragsart, Kategorie].
   */
  public static function arbeiten(): array {
    return [
      'SILAGE' => ['Silo / Schnitt', 'harvest', 'Silo'],
      'HAY' => ['Heu', 'harvest', 'Heu'],
      'MANURE' => ['Gülle', 'input', 'Düngung'],
      'FERTILIZING' => ['Düngung', 'input', 'Düngung'],
      'SPRAYING' => ['Pflanzenschutz', 'input', 'Pflanzenschutz'],
      'HARVEST' => ['Ernte', 'harvest', 'Ernte'],
      'CARE' => ['Pflege / Mulchen', 'activity', 'Pflege'],
      'OTHER' => ['Sonstiges', 'activity', NULL],
    ];
  }

  public static function etm() {
    return \Drupal::entityTypeManager();
  }

  /**
   * Abfrage für nicht archivierte Dinge (farmOS 4: „archived“, farmOS 3: „status“).
   */
  public static function aktiveDinge(string $typ): QueryInterface {
    $q = self::etm()->getStorage('asset')->getQuery()->accessCheck(TRUE)->condition('type', $typ);
    $felder = \Drupal::service('entity_field.manager')->getBaseFieldDefinitions('asset');
    if (isset($felder['archived'])) {
      $q->condition('archived', 0);
    }
    elseif (isset($felder['status'])) {
      $q->condition('status', 'active');
    }
    return $q;
  }

  /**
   * Alle aktiven Felder, nach Namen sortiert (natürlich: 2 vor 10).
   *
   * @return \Drupal\asset\Entity\AssetInterface[]
   */
  public static function felder(): array {
    $ids = self::aktiveDinge('land')->execute();
    $felder = self::etm()->getStorage('asset')->loadMultiple($ids);
    uasort($felder, fn($a, $b) => strnatcasecmp((string) $a->label(), (string) $b->label()));
    return $felder;
  }

  /**
   * Welche Kulturen stehen gerade auf welchem Feld?
   *
   * @return array
   *   Feld-ID => Liste von ['name', 'art', 'seit', 'url'].
   */
  public static function kulturenJeFeld(): array {
    if (!self::etm()->hasDefinition('asset') || !\Drupal::hasService('asset.location')) {
      return [];
    }
    $standort = \Drupal::service('asset.location');
    $ids = self::aktiveDinge('plant')->execute();
    $ergebnis = [];
    foreach (self::etm()->getStorage('asset')->loadMultiple($ids) as $pflanze) {
      $arten = [];
      if ($pflanze->hasField('plant_type')) {
        foreach ($pflanze->get('plant_type')->referencedEntities() as $t) {
          $arten[] = $t->label();
        }
      }
      $seit = NULL;
      try {
        $log = $standort->getMovementLog($pflanze);
        $seit = $log ? (int) $log->get('timestamp')->value : NULL;
      }
      catch (\Throwable $e) {
      }
      foreach ($standort->getLocation($pflanze) as $ort) {
        $ergebnis[$ort->id()][] = [
          'name' => $pflanze->label(),
          'art' => $arten ? implode(', ', $arten) : 'Kultur',
          'seit' => $seit,
          'url' => self::link($pflanze),
        ];
      }
    }
    return $ergebnis;
  }

  /**
   * Markierung „Blühfläche“ in farmOS anlegen, falls sie fehlt (wie in der App).
   */
  public static function bluehflaecheAnlegen(): void {
    if (!self::etm()->hasDefinition('flag')) {
      return;
    }
    $speicher = self::etm()->getStorage('flag');
    if (!$speicher->load(self::BLUEH)) {
      $speicher->create([
        'id' => self::BLUEH,
        'label' => 'Blühfläche',
        'entity_types' => ['asset' => ['land', 'plant']],
      ])->save();
    }
  }

  public static function istBluehflaeche(EntityInterface $ding): bool {
    if (!$ding->hasField('flag')) {
      return FALSE;
    }
    foreach ($ding->get('flag') as $eintrag) {
      if ($eintrag->value === self::BLUEH) {
        return TRUE;
      }
    }
    return FALSE;
  }

  public static function setzeBluehflaeche(EntityInterface $ding, bool $an): void {
    $werte = [];
    foreach ($ding->get('flag') as $eintrag) {
      if ($eintrag->value !== self::BLUEH) {
        $werte[] = $eintrag->value;
      }
    }
    if ($an) {
      $werte[] = self::BLUEH;
    }
    $ding->set('flag', $werte);
    $ding->save();
  }

  /**
   * IDs aller aktiven Felder mit Markierung „Blühfläche“.
   */
  public static function bluehflaechen(): array {
    try {
      return array_map('strval', array_values(self::aktiveDinge('land')->condition('flag', self::BLUEH)->execute()));
    }
    catch (\Throwable $e) {
      return [];
    }
  }

  /**
   * Kategorie suchen oder anlegen.
   */
  public static function begriff(string $vokabular, string $name): TermInterface {
    $speicher = self::etm()->getStorage('taxonomy_term');
    $ids = $speicher->getQuery()->accessCheck(FALSE)
      ->condition('vid', $vokabular)->condition('name', $name)->range(0, 1)->execute();
    if ($ids) {
      return $speicher->load(reset($ids));
    }
    $term = $speicher->create(['vid' => $vokabular, 'name' => $name]);
    $term->save();
    return $term;
  }

  public static function beschreibung(?string $arbeit, ?string $notiz): string {
    $text = 'Feldliste aus richterhofOS';
    if ($arbeit) {
      $text .= "\nArbeit: " . $arbeit;
    }
    if ($notiz = trim((string) $notiz)) {
      $text .= "\nNotiz: " . $notiz;
    }
    return $text;
  }

  /**
   * Arbeit und Notiz aus der Beschreibung einer Liste lesen.
   */
  public static function listenInfo(TermInterface $term): array {
    $text = (string) ($term->get('description')->value ?? '');
    $arbeit = NULL;
    if (preg_match('/Arbeit: ([A-Z_]+)/', $text, $m) && isset(self::arbeiten()[$m[1]])) {
      $arbeit = $m[1];
    }
    $notiz = NULL;
    if (($p = strpos($text, 'Notiz: ')) !== FALSE) {
      $notiz = trim(substr($text, $p + 7)) ?: NULL;
    }
    return [
      'name' => trim(substr((string) $term->label(), strlen(self::LISTE))),
      'arbeit' => $arbeit,
      'notiz' => $notiz,
    ];
  }

  /**
   * Alle aktiven Listen (neueste zuerst).
   *
   * @return \Drupal\taxonomy\TermInterface[]
   */
  public static function listen(): array {
    $speicher = self::etm()->getStorage('taxonomy_term');
    $ids = $speicher->getQuery()->accessCheck(TRUE)
      ->condition('vid', 'log_category')
      ->condition('name', self::LISTE, 'STARTS_WITH')
      ->sort('tid', 'DESC')
      ->execute();
    return $speicher->loadMultiple($ids);
  }

  /**
   * Einträge einer Liste, nach Feld.
   *
   * @return array
   *   Feld-ID => Eintrag (Log). Einträge ohne Feld unter „log-<ID>“.
   */
  public static function listenEintraege(TermInterface $term): array {
    $speicher = self::etm()->getStorage('log');
    $ids = $speicher->getQuery()->accessCheck(TRUE)
      ->condition('category', $term->id())
      ->sort('timestamp', 'ASC')
      ->execute();
    $je_feld = [];
    foreach ($speicher->loadMultiple($ids) as $log) {
      $orte = $log->hasField('location') ? $log->get('location')->referencedEntities() : [];
      if (!$orte) {
        $je_feld['log-' . $log->id()] = $log;
      }
      foreach ($orte as $ort) {
        $je_feld[$ort->id()] = $log;
      }
    }
    return $je_feld;
  }

  /**
   * Einen offenen Listen-Eintrag für ein Feld anlegen.
   */
  public static function eintragAnlegen(TermInterface $liste, string $listenname, ?string $arbeit, ?string $notiz, EntityInterface $feld): void {
    [, $art, $kategorie] = self::arbeiten()[$arbeit ?: 'OTHER'];
    if (!self::etm()->getStorage('log_type')->load($art)) {
      $art = 'activity';
    }
    $kategorien = [$liste->id()];
    if ($kategorie) {
      array_unshift($kategorien, self::begriff('log_category', $kategorie)->id());
    }
    $werte = [
      'type' => $art,
      'name' => $listenname . ' – ' . $feld->label(),
      'timestamp' => \Drupal::time()->getRequestTime(),
      'status' => 'pending',
      'location' => [$feld->id()],
      'category' => $kategorien,
    ];
    if ($notiz) {
      $werte['notes'] = ['value' => $notiz, 'format' => 'default'];
    }
    self::etm()->getStorage('log')->create($werte)->save();
  }

  public static function link(EntityInterface $entity): ?string {
    try {
      return $entity->toUrl()->toString();
    }
    catch (\Throwable $e) {
      return NULL;
    }
  }

  public static function datum(?int $ts): string {
    if (!$ts) {
      return '';
    }
    return date('j.n.Y', $ts);
  }

}
