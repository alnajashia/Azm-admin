package io.github.alnajashia.azmadmin.api;

// Typed result of a privileged operation. The server fills this only after
// post-verification (owner re-read, setting re-read, permission re-check);
// success is never reported from an unchecked command.
parcelable AzmAdminResult;
