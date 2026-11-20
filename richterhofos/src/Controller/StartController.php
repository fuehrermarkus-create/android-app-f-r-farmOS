<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Controller;

use Drupal\Core\Controller\ControllerBase;
use Drupal\Core\Entity\EntityInterface;
use Drupal\Core\Entity\Query\QueryInterface;
use Drupal\Core\Url;
use Drupal\image\Entity\ImageStyle;

/**
 * Hof-Übersicht (/hof): Zahlen, letzte Arbeiten, offene Listen und Fotos.
 */
class StartController extends ControllerBase {

  /**
   * Präfix der Listen-Kategorien, wie sie die App anlegt.
   */
  const LISTE = 'Liste: ';

  public function start(): array {
    $etm = $this->entityTypeManager();
    $stunde = (int) date('G');
    $gruss = $stunde < 11 ? 'Guten Morgen' : ($stunde < 18 ? 'Grüß Gott' : 'Guten Abend');
    $name = $this->currentUser()->getDisplayName();

    $build = [
      '#theme' => 'richterhofos_start',
      '#gruss' => $gruss . ($name ? ', ' . $name : ''),
      '#datum' => $this->datum(time(), TRUE),
      '#zahlen' => $this->zahlen(),
      '#aktionen' => $this->aktionen(),
      '#arbeiten' => $etm->hasDefinition('log') ? $this->arbeiten() : [],
      '#listen' => $etm->hasDefinition('taxonomy_term') ? $this->listen() : [],
      '#fotos' => $this->fotos(),
      '#links' => [
        'fotos' => $this->url('/fotos'),
        'eintraege' => $this->url('/logs'),
      ],
      '#attached' => ['library' => ['richterhofos/start']],
      '#cache' => [
        'contexts' => ['user'],
        'tags' => ['asset_list', 'log_list', 'taxonomy_term_list'],
        'max-age' => 300,
      ],
    ];
    return $build;
  }

  /**
   * Anzahl aktiver Tiere, Felder und Maschinen.
   */
  protected function zahlen(): array {
    $zahlen = [];
    foreach (richterhofos_sichtbare_dinge() as $typ => [$titel]) {
      $anzahl = 0;
      try {
        $q = $this->aktiveDinge()->condition('type', $typ)->count();
        $anzahl = (int) $q->execute();
      }
      catch (\Throwable $e) {
        // Art nicht vorhanden – 0 anzeigen.
      }
      $zahlen[] = [
        'titel' => $titel,
        'anzahl' => $anzahl,
        'typ' => $typ,
        'url' => $this->url('/assets/' . $typ),
      ];
    }
    return $zahlen;
  }

  /**
   * Abfrage für nicht archivierte Dinge (farmOS 4: „archived“, farmOS 3: „status“).
   */
  protected function aktiveDinge(): QueryInterface {
    $q = $this->entityTypeManager()->getStorage('asset')->getQuery()->accessCheck(TRUE);
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
   * Schnellzugriffe zum Anlegen.
   */
  protected function aktionen(): array {
    $aktionen = [];
    $ziele = [
      ['Neue Arbeit', '/log/add/activity', 'log', 'activity'],
      ['Neue Beobachtung', '/log/add/observation', 'log', 'observation'],
      ['Neues Tier', '/asset/add/animal', 'asset', 'animal'],
      ['Neue Maschine', '/asset/add/equipment', 'asset', 'equipment'],
    ];
    foreach ($ziele as [$titel, $pfad, $art, $typ]) {
      if (!$this->entityTypeManager()->hasDefinition($art . '_type')
        || !$this->entityTypeManager()->getStorage($art . '_type')->load($typ)) {
        continue;
      }
      $url = $this->url($pfad);
      if ($url) {
        $aktionen[] = ['titel' => $titel, 'url' => $url];
      }
    }
    return $aktionen;
  }

  /**
   * Die letzten zehn Einträge.
   */
  protected function arbeiten(): array {
    $speicher = $this->entityTypeManager()->getStorage('log');
    $ids = $speicher->getQuery()->accessCheck(TRUE)
      ->sort('timestamp', 'DESC')
      ->range(0, 10)
      ->execute();
    $bundles = \Drupal::service('entity_type.bundle.info')->getBundleInfo('log');
    $arbeiten = [];
    foreach ($speicher->loadMultiple($ids) as $log) {
      $orte = [];
      foreach (['location', 'asset'] as $feld) {
        if ($log->hasField($feld)) {
          foreach ($log->get($feld)->referencedEntities() as $ding) {
            $orte[$ding->id()] = $ding->label();
          }
        }
      }
      $arbeiten[] = [
        'titel' => $log->label(),
        'art' => $bundles[$log->bundle()]['label'] ?? $log->bundle(),
        'datum' => $this->datum((int) $log->get('timestamp')->value),
        'offen' => $log->hasField('status') && $log->get('status')->value === 'pending',
        'wo' => implode(', ', array_slice($orte, 0, 3)) . (count($orte) > 3 ? ' …' : ''),
        'url' => $this->entityUrl($log),
      ];
    }
    return $arbeiten;
  }

  /**
   * Listen aus der App („Liste: …“) mit Fortschritt.
   */
  protected function listen(): array {
    $terme = $this->entityTypeManager()->getStorage('taxonomy_term');
    $tids = $terme->getQuery()->accessCheck(TRUE)
      ->condition('vid', 'log_category')
      ->condition('name', self::LISTE, 'STARTS_WITH')
      ->sort('tid', 'DESC')
      ->execute();
    if (!$tids) {
      return [];
    }
    $logs = $this->entityTypeManager()->getStorage('log');
    $listen = [];
    foreach ($terme->loadMultiple($tids) as $term) {
      $gesamt = (int) $logs->getQuery()->accessCheck(TRUE)
        ->condition('category', $term->id())->count()->execute();
      $offen = (int) $logs->getQuery()->accessCheck(TRUE)
        ->condition('category', $term->id())
        ->condition('status', 'pending')->count()->execute();
      $fertig = $gesamt - $offen;
      $listen[] = [
        'titel' => trim(substr($term->label(), strlen(self::LISTE))),
        'gesamt' => $gesamt,
        'fertig' => $fertig,
        'offen' => $offen,
        'prozent' => $gesamt ? (int) round($fertig * 100 / $gesamt) : 0,
        'url' => $this->listenUrl((int) $term->id()),
      ];
    }
    // Offene Listen zuerst, dann die neuesten.
    usort($listen, fn($a, $b) => ($b['offen'] > 0) <=> ($a['offen'] > 0));
    return array_slice($listen, 0, 6);
  }

  /**
   * Die letzten acht Fotos aus Einträgen.
   */
  protected function fotos(): array {
    $etm = $this->entityTypeManager();
    if (!$etm->hasDefinition('log')) {
      return [];
    }
    $felder = \Drupal::service('entity_field.manager')->getFieldStorageDefinitions('log');
    if (!isset($felder['image'])) {
      return [];
    }
    $ids = $etm->getStorage('log')->getQuery()->accessCheck(TRUE)
      ->exists('image')
      ->sort('timestamp', 'DESC')
      ->range(0, 8)
      ->execute();
    $stil = ImageStyle::load('medium') ?: ImageStyle::load('thumbnail');
    $fotos = [];
    foreach ($etm->getStorage('log')->loadMultiple($ids) as $log) {
      $datei = $log->get('image')->entity;
      if (!$datei) {
        continue;
      }
      $uri = $datei->getFileUri();
      $fotos[] = [
        'bild' => $stil ? $stil->buildUrl($uri) : \Drupal::service('file_url_generator')->generateString($uri),
        'titel' => $log->label(),
        'datum' => $this->datum((int) $log->get('timestamp')->value),
        'url' => $this->entityUrl($log),
      ];
    }
    return $fotos;
  }

  protected function datum(int $ts, bool $lang = FALSE): string {
    if (!$ts) {
      return '';
    }
    $tage = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];
    $tageLang = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
    $w = (int) date('w', $ts);
    return $lang
      ? $tageLang[$w] . ', ' . date('j.n.Y', $ts)
      : $tage[$w] . ' ' . date('j.n.', $ts) . (date('Y', $ts) !== date('Y') ? date('Y', $ts) : '');
  }

  protected function listenUrl(int $tid): ?string {
    try {
      $url = Url::fromUserInput('/logs', ['query' => ['category_target_id' => [$tid]]]);
      return $url->access() ? $url->toString() : NULL;
    }
    catch (\Throwable $e) {
      return NULL;
    }
  }

  protected function url(string $pfad): ?string {
    try {
      $url = Url::fromUserInput($pfad);
      return $url->access() ? $url->toString() : NULL;
    }
    catch (\Throwable $e) {
      return NULL;
    }
  }

  protected function entityUrl(EntityInterface $entity): ?string {
    try {
      return $entity->toUrl()->toString();
    }
    catch (\Throwable $e) {
      return NULL;
    }
  }

}
