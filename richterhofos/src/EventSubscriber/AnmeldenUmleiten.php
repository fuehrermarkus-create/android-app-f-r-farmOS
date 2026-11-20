<?php

declare(strict_types=1);

namespace Drupal\richterhofos\EventSubscriber;

use Drupal\Core\Routing\LocalRedirectResponse;
use Drupal\Core\Routing\RouteMatchInterface;
use Drupal\Core\Session\AccountInterface;
use Drupal\Core\Url;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\KernelEvents;

/**
 * Nicht angemeldete Besucher landen auf der Anmeldung statt auf „Zugriff verweigert“.
 *
 * Gilt für die Seiten von richterhofOS, vor allem die Startseite /hof.
 */
class AnmeldenUmleiten implements EventSubscriberInterface {

  public function __construct(
    protected AccountInterface $user,
    protected RouteMatchInterface $routeMatch,
  ) {}

  public static function getSubscribedEvents(): array {
    // Vor Drupals eigener 403-Seite (Priorität -50 bis 0).
    return [KernelEvents::EXCEPTION => ['beiFehler', 100]];
  }

  public function beiFehler(ExceptionEvent $event): void {
    if (!$event->getThrowable() instanceof AccessDeniedHttpException || $this->user->isAuthenticated()) {
      return;
    }
    $request = $event->getRequest();
    $route = (string) $request->attributes->get('_route', '');
    $pfad = $request->getPathInfo();
    if (!str_starts_with($route, 'richterhofos.') && $pfad !== '/' && $pfad !== '/hof') {
      return;
    }
    $ziel = $pfad === '/' ? '/hof' : $pfad;
    if ($q = $request->getQueryString()) {
      $ziel .= '?' . $q;
    }
    $url = Url::fromRoute('user.login', [], ['query' => ['destination' => $ziel]])->toString();
    $event->setResponse(new LocalRedirectResponse($url));
  }

}
