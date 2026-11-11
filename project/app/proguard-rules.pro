# The privileged server entry point is launched by name through app_process;
# keep it and the AIDL machinery intact in release builds.
-keep class io.github.alnajashia.azmadmin.server.AzmAdminServerMain { *; }
-keep class io.github.alnajashia.azmadmin.api.** { *; }
-keep class io.github.alnajashia.azmadmin.client.** { *; }
