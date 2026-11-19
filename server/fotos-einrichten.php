<?php
/**
 * farmOS Hof – legt die Seite „Fotos“ (/fotos) an bzw. aktualisiert sie.
 */
$datei = '/tmp/fotos-view.yml';
$daten = \Drupal\Core\Serialization\Yaml::decode(file_get_contents($datei));
$ansichten = \Drupal::entityTypeManager()->getStorage('view');
if ($alt = $ansichten->load($daten['id'])) {
  $alt->delete();
  echo "Alte Seite „Fotos“ entfernt\n";
}
$ansichten->create($daten)->save();

// Alle Feld-Optionen mit den Standardwerten von Views auffüllen
// (farmOS 4 / Drupal 11 braucht z. B. 'alter', sonst Fehler beim Anzeigen).
$view = \Drupal\views\Views::getView($daten['id']);
foreach (array_keys($daten['display']) as $display_id) {
  if (!$view->setDisplay($display_id)) {
    continue;
  }
  foreach (['field', 'filter', 'sort', 'argument', 'relationship'] as $typ) {
    foreach ($view->display_handler->getHandlers($typ) as $id => $handler) {
      $view->setHandler($display_id, $typ, $id, $handler->options);
    }
  }
}
$view->storage->save();
echo "Feld-Einstellungen vervollständigt\n";
\Drupal::service('router.builder')->rebuild();
echo "Seite „Fotos“ angelegt: /fotos\n";
