package io.github.alnajashia.azmadmin.client

/** Client-side failure: no live binder, server not running, or a remote error. */
class AzmAdminException(message: String, cause: Throwable? = null) : Exception(message, cause)
