<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Controller;

use Drupal\Component\Utility\Html;
use Drupal\Core\Controller\ControllerBase;
use Drupal\Core\Link;
use Drupal\Core\Url;
use Drupal\richterhofos\Hof;
use Drupal\taxonomy\TermInterface;

/**
 * Listen-Übersicht und „Was wächst wo“.
 */
class ListenController extends ControllerBase {

  /**
   * /listen – alle Listen mit Fortschritt.
   */
  public function uebersicht(): array {
    $build = [
      '#attached' => ['library' => ['richterhofos/listen']],
      '#cache' => ['tags' => ['log_list', 'taxonomy_term_list'], 'contexts' => ['user']],
      'kopf' => [
        '#type' => 'container',
        '#attributes' => ['class' => ['rh-kopfzeile']],
        'text' => ['#markup' => '<p class="rh-hinweis">Listen siehst du auch in der App. Häkchen, die du hier oder am Handy setzt, werden abgeglichen.</p>'],
      ],
    ];
    $neu = Url::fromRoute('richterhofos.liste_neu');
    if ($neu->access()) {
      $build['kopf']['neu'] = Link::fromTextAndUrl('+ Neue Liste', $neu)->toRenderable() + [
        '#attributes' => ['class' => ['rh-knopf', 'rh-knopf--haupt']],
      ];
    }

    $karten = [];
    foreach (Hof::listen() as $term) {
      $info = Hof::listenInfo($term);
      $eintraege = Hof::listenEintraege($term);
      $gesamt = count($eintraege);
      $fertig = count(array_filter($eintraege, fn($l) => $l->get('status')->value !== 'pending'));
      $prozent = $gesamt ? (int) round($fertig * 100 / $gesamt) : 0;
      $arbeit = $info['arbeit'] ? Hof::arbeiten()[$info['arbeit']][0] : '';
      $karten[] = [
        'url' => Url::fromRoute('richterhofos.liste', ['taxonomy_term' => $term->id()])->toString(),
        'name' => $info['name'],
        'arbeit' => $arbeit,
        'prozent' => $prozent,
        'fertig' => $fertig,
        'gesamt' => $gesamt,
      ];
    }
    $build['karten'] = [
      '#type' => 'inline_template',
      '#template' => '{% if karten %}<div class="rh-listenraster">{% for k in karten %}'
        . '<a class="rh-listenkarte" href="{{ k.url }}">'
        . '<span class="rh-listenkarte__titel">{{ k.name }}</span>'
        . '{% if k.arbeit %}<span class="rh-marke">{{ k.arbeit }}</span>{% endif %}'
        . '<span class="rh-balken"><span style="width: {{ k.prozent }}%"></span></span>'
        . '<span class="rh-listenkarte__zahl">{{ k.fertig }} von {{ k.gesamt }} Feldern erledigt</span>'
        . '</a>{% endfor %}</div>'
        . '{% else %}<p class="rh-leer">Noch keine Listen. Lege mit „+ Neue Liste“ die erste an, z. B. „Silo erster Schnitt“.</p>{% endif %}',
      '#context' => ['karten' => $karten],
    ];
    return $build;
  }

  /**
   * Titel einer Liste.
   */
  public function titel(TermInterface $taxonomy_term): string {
    return Hof::listenInfo($taxonomy_term)['name'] ?: 'Liste';
  }

  /**
   * /kulturen – welche Frucht steht auf welchem Feld.
   */
  public function kulturen(): array {
    $request = \Drupal::request();
    $filter = (string) $request->query->get('kultur', '');
    $kulturen = Hof::kulturenJeFeld();
    $felder = Hof::felder();

    // Zusammenfassung je Kulturart.
    $je_art = [];
    $leer = 0;
    foreach ($felder as $id => $feld) {
      if (empty($kulturen[$id])) {
        $leer++;
        continue;
      }
      foreach (array_unique(array_column($kulturen[$id], 'art')) as $art) {
        $je_art[$art] = ($je_art[$art] ?? 0) + 1;
      }
    }
    ksort($je_art, SORT_NATURAL | SORT_FLAG_CASE);
    $blueh = array_flip(Hof::bluehflaechen());

    $chips = [
      '#type' => 'inline_template',
      '#template' => '<nav class="rh-chips" aria-label="Nach Kultur filtern">'
        . '<a class="rh-chip{{ filter == \'\' ? \' is-aktiv\' }}" href="?">Alle <b>{{ alle }}</b></a>'
        . '{% for art, n in arten %}<a class="rh-chip{{ filter == art ? \' is-aktiv\' }}" href="?kultur={{ art|url_encode }}">{{ art }} <b>{{ n }}</b></a>{% endfor %}'
        . '{% if leer %}<a class="rh-chip{{ filter == \'-\' ? \' is-aktiv\' }}" href="?kultur=-">ohne Kultur <b>{{ leer }}</b></a>{% endif %}'
        . '{% if blueh %}<a class="rh-chip rh-chip--blueh{{ filter == \'bluehflaeche\' ? \' is-aktiv\' }}" href="?kultur=bluehflaeche">🌼 Blühflächen <b>{{ blueh }}</b></a>{% endif %}'
        . '<button type="button" class="rh-chip rh-drucken">Drucken</button></nav>',
      '#context' => ['filter' => $filter, 'alle' => count($felder), 'arten' => $je_art, 'leer' => $leer, 'blueh' => count(array_intersect_key($blueh, $felder))],
    ];

    $zeilen = [];
    foreach ($felder as $id => $feld) {
      $hier = $kulturen[$id] ?? [];
      $arten = array_unique(array_column($hier, 'art'));
      $ist_blueh = isset($blueh[(string) $id]);
      if ($filter === '-' && $hier) {
        continue;
      }
      if ($filter === 'bluehflaeche') {
        if (!$ist_blueh) {
          continue;
        }
      }
      elseif ($filter !== '' && $filter !== '-' && !in_array($filter, $arten, TRUE)) {
        continue;
      }
      $url = Hof::link($feld);
      $feldzelle = $url ? Link::fromTextAndUrl($feld->label(), Url::fromUserInput($url)) : $feld->label();
      $schalter = $this->bluehSchalter($feld, $ist_blueh, $filter);
      if ($hier) {
        $namen = [];
        $seit = [];
        foreach ($hier as $k) {
          $namen[] = $k['url']
            ? '<a href="' . $k['url'] . '">' . Html::escape($k['name']) . '</a>'
            : Html::escape($k['name']);
          if ($k['seit']) {
            $seit[] = Hof::datum($k['seit']);
          }
        }
        $zeilen[] = [
          ['data' => $feldzelle, 'class' => ['rh-feld']],
          ['data' => ['#markup' => '<span class="rh-marke">' . Html::escape(implode(', ', $arten)) . '</span>']],
          ['data' => ['#markup' => implode('<br>', $namen)]],
          implode(', ', array_unique($seit)),
          ['data' => $schalter],
        ];
      }
      else {
        $zeilen[] = [
          ['data' => $feldzelle, 'class' => ['rh-feld']],
          ['data' => ['#markup' => '<span class="rh-leer">–</span>']],
          '',
          '',
          ['data' => $schalter],
        ];
      }
    }

    return [
      '#attached' => ['library' => ['richterhofos/listen']],
      '#cache' => ['tags' => ['asset_list', 'log_list', 'config:flag_list'], 'contexts' => ['url.query_args:kultur', 'user']],
      'hinweis' => ['#markup' => '<p class="rh-hinweis">Kulturen entstehen in der App bei „Aussaat“. Geerntete oder beendete Kulturen in farmOS archivieren, dann verschwinden sie hier.</p>'],
      'chips' => $chips,
      'tabelle' => [
        '#type' => 'table',
        '#header' => ['Feld', 'Frucht', 'Kultur', 'Seit', 'Blühfläche'],
        '#rows' => $zeilen,
        '#empty' => 'Keine Felder gefunden.',
        '#attributes' => ['class' => ['rh-tabelle']],
      ],
    ];
  }

  /**
   * Knopf in der Spalte „Blühfläche“: zeigt den Stand und schaltet ihn um.
   */
  protected function bluehSchalter($feld, bool $an, string $filter): array {
    $url = Url::fromRoute('richterhofos.bluehflaeche', ['asset' => $feld->id()], ['query' => ['kultur' => $filter]]);
    if (!$url->access()) {
      return ['#markup' => $an ? '🌼' : ''];
    }
    return Link::fromTextAndUrl($an ? '🌼 ja' : '+ markieren', $url)->toRenderable() + [
      '#attributes' => [
        'class' => ['rh-blueh', $an ? 'is-an' : 'is-aus'],
        'title' => $an ? 'Markierung Blühfläche entfernen' : 'Als Blühfläche markieren',
      ],
    ];
  }

  /**
   * Markierung „Blühfläche“ eines Felds umschalten und zurück zur Liste.
   */
  public function bluehflaecheUmschalten(\Drupal\Core\Entity\EntityInterface $asset) {
    Hof::bluehflaecheAnlegen();
    $an = !Hof::istBluehflaeche($asset);
    Hof::setzeBluehflaeche($asset, $an);
    $this->messenger()->addStatus($asset->label() . ($an ? ' ist jetzt als Blühfläche markiert.' : ' ist keine Blühfläche mehr.'));
    $kultur = (string) \Drupal::request()->query->get('kultur', '');
    $ziel = Url::fromRoute('richterhofos.kulturen', [], $kultur !== '' ? ['query' => ['kultur' => $kultur]] : []);
    return new \Symfony\Component\HttpFoundation\RedirectResponse($ziel->toString());
  }

}
