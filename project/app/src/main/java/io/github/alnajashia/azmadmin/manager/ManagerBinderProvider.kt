package io.github.alnajashia.azmadmin.manager

import io.github.alnajashia.azmadmin.client.AzmAdminProvider

/**
 * The manager app's binder provider (authority
 * `io.github.alnajashia.azmadmin.binder`). The privileged server pushes its
 * binder here on startup; client apps pull it from here through the client
 * library. Behaviour is entirely [AzmAdminProvider]'s.
 */
class ManagerBinderProvider : AzmAdminProvider()
