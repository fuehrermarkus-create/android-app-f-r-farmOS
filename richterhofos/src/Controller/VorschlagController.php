<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Controller;

use Drupal\Core\Controller\ControllerBase;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * Vorschläge beim Tippen für Dünger, Mittel und Früchte.
 */
class VorschlagController extends ControllerBase {

  public function vorschlag(Request $request, string $vokabular): JsonResponse {
    if (!in_array($vokabular, ['material_type', 'plant_type'], TRUE)) {
      throw new NotFoundHttpException();
    }
    $text = trim((string) $request->query->get('q', ''));
    $abfrage = $this->entityTypeManager()->getStorage('taxonomy_term')->getQuery()->accessCheck(TRUE)
      ->condition('vid', $vokabular)
      ->sort('name')
      ->range(0, 15);
    if ($text !== '') {
      $abfrage->condition('name', $text, 'CONTAINS');
    }
    $treffer = [];
    foreach ($this->entityTypeManager()->getStorage('taxonomy_term')->loadMultiple($abfrage->execute()) as $t) {
      $treffer[] = ['value' => $t->label(), 'label' => $t->label()];
    }
    return new JsonResponse($treffer);
  }

}
