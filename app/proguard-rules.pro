# R8 keep rules.
#
# sshj and BouncyCastle pick implementations by name at runtime, so a class reached only that way
# looks unused to R8 and gets shrunk away. It fails silently: an auth failure, a missing algorithm
# or a hang, never a clean ClassNotFoundException. Keep the rules narrow and tied to something that
# actually broke.

# Transport, cipher, kex, mac, compression, signature and key-file formats are all resolved by
# algorithm name through NamedFactory registries, never by a `new` R8 can trace.
-keep class net.schmizz.sshj.** { *; }
-keep interface net.schmizz.sshj.** { *; }

# The JCA provider is resolved by name through java.security.Security, and the PEM/OpenSSH key
# parsers (including passphrase-encrypted keys) are instantiated the same way.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }

# minifyRelease failed outright with "Missing classes detected" for javax.naming.* (BouncyCastle's
# JNDI/LDAP cert stores), org.ietf.jgss.* and javax.security.auth.login.LoginContext (sshj's GSSAPI
# auth). None of it exists on Android and none of it is reachable from here, so -dontwarn rather
# than -keep: there is nothing to keep.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
