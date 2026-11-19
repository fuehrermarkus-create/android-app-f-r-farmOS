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
\Drupal::service('router.builder')->rebuild();
echo "Seite „Fotos“ angelegt: /fotos\n";
