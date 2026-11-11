# The client AIDL stubs and parcelables are looked up reflectively by the
# binder machinery and by Bundle unmarshalling; keep them in consumer builds.
-keep class io.github.alnajashia.azmadmin.api.** { *; }
-keep class io.github.alnajashia.azmadmin.client.AzmAdminProvider { *; }
