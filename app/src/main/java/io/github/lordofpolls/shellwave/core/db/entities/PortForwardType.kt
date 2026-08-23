package io.github.lordofpolls.shellwave.core.db.entities

/** [DYNAMIC] is a SOCKS5 listener, so it leaves `targetHost`/`targetPort` null. */
enum class PortForwardType { LOCAL, REMOTE, DYNAMIC }
